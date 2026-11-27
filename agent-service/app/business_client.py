import asyncio
import json
import logging
import re
import time
from dataclasses import dataclass, field

import httpx
from pydantic import BaseModel, ConfigDict, Field

from app.errors import ServiceError, unavailable

LOG = logging.getLogger("capyiot.agent.business")


class CurrentUser(BaseModel):
    model_config = ConfigDict(extra="ignore")
    userId: str
    sessionId: str
    status: str = "ACTIVE"
    authorizationVersion: int
    permissionCodes: list[str]
    roleCodes: list[str] = Field(default_factory=list)


@dataclass(frozen=True)
class Principal:
    user_id: str
    session_id: str
    authorization_version: int
    permissions: frozenset[str]
    token: str = field(repr=False)

    def require(self, permission):
        if permission not in self.permissions:
            raise ServiceError("FORBIDDEN", 403, "当前用户无权执行此操作")


def identifier(value):
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", value):
        raise ServiceError("INVALID_ARGUMENT", 400, "资源标识无效")
    return value


class BusinessApiClient:
    """Only fixed business routes, with user context injected by server code."""

    def __init__(self, http: httpx.AsyncClient, settings, service_identity=None):
        self.http, self.origins = http, settings.business_origins
        self.identity = service_identity

    @staticmethod
    def owner(route):
        if route.startswith(("/workorders", "/workorder-operations")) or route.endswith(
            "/maintenance-history"
        ):
            return "workflow"
        if route.startswith(("/anomalies", "/incidents")):
            return "fault"
        return "device"

    async def _request(
        self, route, token, correlation, *, params=None, body=None, key=None, identity=False
    ):
        method = "GET" if body is None else "POST"
        if (
            method == "POST"
            and not identity
            and (not key or not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", key))
        ):
            raise ServiceError("INVALID_ARGUMENT", 400, "写请求必须提供有效的幂等键")
        headers = {
            "Authorization": "Bearer " + token,
            "X-Correlation-Id": correlation,
            "X-Request-Id": correlation,
            "Content-Type": "application/json",
        }
        if key:
            headers["Idempotency-Key"] = key
        content_bytes = (
            None
            if body is None
            else json.dumps(
                body, sort_keys=True, separators=(",", ":"), ensure_ascii=False
            ).encode()
        )
        query = method == "GET" or identity
        origin = self.origins["platform" if identity else self.owner(route)]
        path = route if identity else "/api/v1" + route
        # JWT iat is whole seconds; an eight-second budget also stays below Java's iat+10s ceiling.
        deadline = int(time.time() * 1000) + 8000
        try:
            async with asyncio.timeout(8):
                return await self._exchange(
                    method,
                    origin + path,
                    headers,
                    params,
                    content_bytes,
                    token,
                    path,
                    correlation,
                    deadline,
                    query,
                    identity,
                )
        except TimeoutError:
            raise ServiceError(
                "UPSTREAM_TIMEOUT" if query else "COMMAND_OUTCOME_UNKNOWN",
                503,
                "依赖服务超时" if query else "命令结果未知，需使用原幂等键核对",
            ) from None

    async def _exchange(
        self,
        method,
        url,
        headers,
        params,
        content_bytes,
        token,
        path,
        correlation,
        deadline,
        query,
        identity,
    ):
        for attempt in range(2 if query else 1):
            if identity:
                if self.identity is None:
                    raise unavailable("SERVICE_IDENTITY_UNAVAILABLE")
                headers.update(self.identity.headers(token, content_bytes, deadline))
            start = time.monotonic()
            try:
                async with self.http.stream(
                    method,
                    url,
                    headers=headers,
                    params=params,
                    content=content_bytes,
                    timeout=3,
                ) as response:
                    if response.status_code >= 500 and query and attempt == 0:
                        await asyncio.sleep(0.1)
                        continue
                    chunks, size = [], 0
                    async for chunk in response.aiter_bytes():
                        size += len(chunk)
                        if size > 1_048_576:
                            if not query:
                                raise ServiceError(
                                    "COMMAND_OUTCOME_UNKNOWN",
                                    503,
                                    "命令结果未知，需使用原幂等键核对",
                                )
                            raise ServiceError(
                                "UPSTREAM_INVALID_RESPONSE", 502, "业务服务返回的数据超出上限"
                            )
                        chunks.append(chunk)
                    content = b"".join(chunks)
                    if 300 <= response.status_code < 400:
                        raise ServiceError(
                            "UPSTREAM_INVALID_RESPONSE" if query else "COMMAND_OUTCOME_UNKNOWN",
                            502 if query else 503,
                            "依赖服务响应无效，命令结果请使用原幂等键核对",
                        )
                    LOG.info(
                        "business_completed correlation=%s method=%s status=%s latency_ms=%s",
                        correlation,
                        method,
                        response.status_code,
                        round((time.monotonic() - start) * 1000),
                    )
                    if response.status_code >= 400:
                        status = response.status_code
                        try:
                            failure = json.loads(content)
                            operation_id = failure.get("details", {}).get("operationId")
                            operation_code = failure.get("code")
                            if (
                                path.startswith("/api/v1/workorders")
                                and (status, operation_code)
                                in {
                                    (409, "WORKORDER_OPERATION_ABORTED"),
                                    (503, "WORKORDER_OPERATION_PENDING"),
                                }
                                and isinstance(operation_id, str)
                                and re.fullmatch(r"[A-Za-z0-9_-]{1,128}", operation_id)
                            ):
                                raise ServiceError(
                                    operation_code,
                                    status,
                                    "工单操作已终止"
                                    if status == 409
                                    else "工单操作正在核对，请勿重复建单",
                                    {"operationId": operation_id},
                                )
                        except (ValueError, AttributeError, TypeError):
                            pass
                        if not query and status >= 500:
                            raise ServiceError(
                                "COMMAND_OUTCOME_UNKNOWN", 503, "命令结果未知，需使用原幂等键核对"
                            )
                        code, message = {
                            400: ("INVALID_ARGUMENT", "业务请求参数无效"),
                            401: ("UNAUTHENTICATED", "登录已失效，请重新登录"),
                            403: ("FORBIDDEN", "当前用户无权执行此操作"),
                            404: ("NOT_FOUND", "资源不存在或不可访问"),
                            409: ("CONFLICT", "业务状态或幂等请求冲突"),
                        }.get(status, ("UPSTREAM_UNAVAILABLE", "业务服务暂不可用"))
                        raise ServiceError(
                            code, status if status in {400, 401, 403, 404, 409} else 503, message
                        )
                    try:
                        payload = json.loads(content)
                        if payload.get("code") != "OK" or "data" not in payload:
                            raise ValueError("Invalid envelope")
                        return payload["data"]
                    except (ValueError, AttributeError):
                        if not query:
                            raise ServiceError(
                                "COMMAND_OUTCOME_UNKNOWN", 503, "命令结果未知，需使用原幂等键核对"
                            ) from None
                        raise ServiceError(
                            "UPSTREAM_INVALID_RESPONSE", 502, "业务服务响应格式无效"
                        ) from None
            except httpx.HTTPError as error:
                if query and attempt == 0:
                    await asyncio.sleep(0.1)
                    continue
                if not query:
                    raise ServiceError(
                        "COMMAND_OUTCOME_UNKNOWN", 503, "命令结果未知，需使用原幂等键核对"
                    ) from None
                if isinstance(error, httpx.TimeoutException):
                    raise ServiceError("UPSTREAM_TIMEOUT", 504, "业务服务请求超时") from None
                raise unavailable() from None

    async def current_user(self, token, correlation):
        from pydantic import ValidationError

        try:
            data = await self._request(
                "/internal/v1/identity/context", token, correlation, body={}, identity=True
            )
            return CurrentUser.model_validate(data)
        except ValidationError:
            raise unavailable("UPSTREAM_INVALID_RESPONSE") from None

    async def read(
        self, operation, principal: Principal, correlation, *, identity=None, filters=None
    ):
        routes = {
            "query_devices": (
                "/devices",
                "device.read",
                {
                    "keyword",
                    "deviceType",
                    "assetStatus",
                    "connectivityStatus",
                    "monitoringPointId",
                    "operatingMode",
                    "dataFreshness",
                    "areaId",
                    "areaScope",
                    "page",
                    "size",
                },
            ),
            "get_device_detail": ("/devices/{id}", "device.read", set()),
            "get_device_status_history": (
                "/devices/{id}/status-history",
                "device.read",
                {"page", "size", "from", "to", "stateDimension"},
            ),
            "query_anomalies": (
                "/anomalies",
                "anomaly.read",
                {"deviceId", "status", "severity", "anomalyType", "page", "size", "from", "to"},
            ),
            "query_incidents": (
                "/incidents",
                "incident.read",
                {"deviceId", "areaId", "status", "priority", "page", "size", "from", "to"},
            ),
            "get_incident_detail": ("/incidents/{id}", "incident.read", set()),
            "get_topology_context": (
                "/topology/context",
                "topology.read",
                {"deviceId", "monitoringPointId", "depth", "asOf"},
            ),
            "query_work_orders": (
                "/workorders",
                "workorder.read",
                {
                    "deviceId",
                    "incidentId",
                    "status",
                    "priority",
                    "assignedRepairerId",
                    "designatedAcceptorId",
                    "page",
                    "size",
                    "from",
                    "to",
                },
            ),
            "get_maintenance_history": (
                "/devices/{id}/maintenance-history",
                "workorder.read",
                {"page", "size", "from", "to"},
            ),
            "_get_work_order": ("/workorders/{id}", "workorder.read", set()),
        }
        if operation not in routes:
            raise ServiceError("TOOL_NOT_ALLOWED", 403, "该业务操作未开放")
        route, permission, allowed = routes[operation]
        principal.require("agent.use")
        principal.require(permission)
        values = dict(filters or {})
        if set(values) - allowed:
            raise ServiceError("INVALID_ARGUMENT", 400, "包含不允许的查询参数")
        if "size" in values and (type(values["size"]) is not int or not 1 <= values["size"] <= 50):
            raise ServiceError("INVALID_ARGUMENT", 400, "查询条数超出上限")
        if "page" in values and (type(values["page"]) is not int or values["page"] < 1):
            raise ServiceError("INVALID_ARGUMENT", 400, "页码无效")
        if "depth" in values and (
            type(values["depth"]) is not int or not 1 <= values["depth"] <= 3
        ):
            raise ServiceError("INVALID_ARGUMENT", 400, "拓扑深度超出上限")
        if "{id}" in route:
            route = route.replace("{id}", identifier(identity))
        return await self._request(route, principal.token, correlation, params=values)

    async def create_work_order(self, principal: Principal, correlation, payload, idempotency_key):
        # Transport foundation for phase 4; never registered as a model tool.
        principal.require("agent.draft.confirm")
        principal.require("workorder.create")
        return await self._request(
            "/workorders", principal.token, correlation, body=payload, key=idempotency_key
        )

    async def validate_work_order(self, principal, correlation, payload):
        principal.require("agent.draft.prepare")
        principal.require("workorder.create")
        return await self._request(
            "/workorders/validate",
            principal.token,
            correlation,
            body=payload,
            key="validate-" + correlation,
        )

    async def dispatch_work_order(self, command, token):
        return await self._request(
            "/workorders",
            token,
            command["commandId"],
            body=command["payload"],
            key=command["idempotencyKey"],
        )

    async def command_receipt(self, command, token):
        return await self._request(
            "/workorders/commands/" + identifier(command["idempotencyKey"]),
            token,
            command["commandId"],
        )
