import asyncio
import logging
import re
import time
from dataclasses import dataclass, field

import httpx
from pydantic import BaseModel, ConfigDict, Field

from app.errors import ServiceError, unavailable

LOG = logging.getLogger("capyiot.agent.core")


class CurrentUser(BaseModel):
    model_config = ConfigDict(extra="ignore")
    userId: str
    status: str
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


class CoreApiClient:
    """Only fixed business routes, with user context injected by server code."""
    def __init__(self, http: httpx.AsyncClient, base_url: str):
        self.http, self.base_url = http, base_url.rstrip("/")

    async def _request(self, route, token, correlation, *, params=None, body=None, key=None):
        method = "GET" if body is None else "POST"
        if method == "POST" and (not key or not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", key)):
            raise ServiceError("INVALID_ARGUMENT", 400, "写请求必须提供有效的幂等键")
        headers = {"Authorization": "Bearer " + token, "X-Correlation-Id": correlation,
                   "X-Request-Id": correlation}
        if key:
            headers["Idempotency-Key"] = key
        for attempt in range(2 if method == "GET" else 1):
            start = time.monotonic()
            try:
                async with self.http.stream(method, self.base_url + "/api/v1" + route,
                                            headers=headers, params=params, json=body) as response:
                    if response.status_code >= 500 and method == "GET" and attempt == 0:
                        await asyncio.sleep(0.1)
                        continue
                    chunks, size = [], 0
                    async for chunk in response.aiter_bytes():
                        size += len(chunk)
                        if size > 1_048_576:
                            raise ServiceError("UPSTREAM_INVALID_RESPONSE", 502, "业务服务返回的数据超出上限")
                        chunks.append(chunk)
                    content = b"".join(chunks)
                    LOG.info("core_completed correlation=%s method=%s status=%s latency_ms=%s",
                             correlation, method, response.status_code, round((time.monotonic()-start)*1000))
                    if response.status_code >= 400:
                        status = response.status_code
                        code, message = {
                            400: ("INVALID_ARGUMENT", "业务请求参数无效"),
                            401: ("UNAUTHENTICATED", "登录已失效，请重新登录"),
                            403: ("FORBIDDEN", "当前用户无权执行此操作"),
                            404: ("NOT_FOUND", "资源不存在或不可访问"),
                            409: ("CONFLICT", "业务状态或幂等请求冲突"),
                        }.get(status, ("UPSTREAM_UNAVAILABLE", "业务服务暂不可用"))
                        raise ServiceError(code, status if status in {400,401,403,404,409} else 503, message)
                    import json
                    try:
                        payload = json.loads(content)
                        if payload.get("code") != "OK" or "data" not in payload:
                            raise ValueError("Invalid envelope")
                        return payload["data"]
                    except (ValueError, AttributeError):
                        raise ServiceError("UPSTREAM_INVALID_RESPONSE", 502, "业务服务响应格式无效") from None
            except httpx.HTTPError as error:
                if method == "GET" and attempt == 0:
                    await asyncio.sleep(0.1)
                    continue
                if method == "POST":
                    raise ServiceError("COMMAND_OUTCOME_UNKNOWN", 503, "命令结果未知，需使用原幂等键核对") from None
                if isinstance(error, httpx.TimeoutException):
                    raise ServiceError("UPSTREAM_TIMEOUT", 504, "业务服务请求超时") from None
                raise unavailable() from None

    async def current_user(self, token, correlation):
        from pydantic import ValidationError
        try:
            return CurrentUser.model_validate(await self._request("/auth/me", token, correlation))
        except ValidationError:
            raise unavailable("UPSTREAM_INVALID_RESPONSE") from None

    async def read(self, operation, principal: Principal, correlation, *, identity=None, filters=None):
        routes = {
            "query_devices": ("/devices", "device.read", {"keyword","deviceType","assetStatus","connectivityStatus","areaId","areaScope","page","size"}),
            "get_device_detail": ("/devices/{id}", "device.read", set()),
            "get_device_status_history": ("/devices/{id}/status-history", "device.read", {"page","size","from","to","stateDimension"}),
            "query_anomalies": ("/anomalies", "anomaly.read", {"deviceId","status","severity","anomalyType","page","size"}),
            "query_incidents": ("/incidents", "incident.read", {"deviceId","status","priority","page","size"}),
            "get_incident_detail": ("/incidents/{id}", "incident.read", set()),
            "get_topology_context": ("/topology/context", "topology.read", {"deviceId","monitoringPointId","depth","asOf"}),
            "query_work_orders": ("/workorders", "workorder.read", {"deviceId","incidentId","status","priority","page","size"}),
            "get_maintenance_history": ("/devices/{id}/maintenance-history", "workorder.read", {"page","size"}),
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
        if "depth" in values and (type(values["depth"]) is not int or not 1 <= values["depth"] <= 3):
            raise ServiceError("INVALID_ARGUMENT", 400, "拓扑深度超出上限")
        if "{id}" in route:
            route = route.replace("{id}", identifier(identity))
        return await self._request(route, principal.token, correlation, params=values)

    async def create_work_order(self, principal: Principal, correlation, payload, idempotency_key):
        # Transport foundation for phase 4; never registered as a model tool.
        principal.require("agent.draft.confirm")
        principal.require("workorder.create")
        return await self._request("/workorders", principal.token, correlation, body=payload, key=idempotency_key)
