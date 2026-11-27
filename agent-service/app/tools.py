import asyncio
import json
import logging
import time
import uuid
from datetime import UTC, datetime

from pydantic import ValidationError
from pydantic_ai import Tool
from starlette.concurrency import run_in_threadpool

from app import tool_schemas as s
from app.errors import ServiceError
from app.repository import fingerprint

LOG = logging.getLogger("capyiot.agent.tools")
CONTRACTS = {
    "query_devices": (
        s.QueryDevices,
        s.Page[s.Device],
        "device",
        "查询当前可见设备及各维状态；空结果只表示当前范围没有匹配记录。",
    ),
    "get_device_detail": (
        s.DeviceIdentity,
        s.DeviceDetail,
        "device",
        "核查指定设备资产、独立状态和关系；在线不代表录像正常。",
    ),
    "get_device_status_history": (
        s.StatusHistory,
        s.Page[s.StateChange],
        "device",
        "查询设备有意义的状态变化，区分发生时间和接收时间。",
    ),
    "query_anomalies": (
        s.QueryAnomalies,
        s.Page[s.Anomaly],
        "fault",
        "查询异常及其恢复状态，不把异常等同于已确认故障。",
    ),
    "get_incident_detail": (
        s.IncidentIdentity,
        s.IncidentDetail,
        "fault",
        "读取故障证据和根因状态；候选根因不等于已确认根因。",
    ),
    "query_incidents": (
        s.QueryIncidents,
        s.Page[s.Incident],
        "fault",
        "查询当前可见故障，按状态、优先级、设备或区域筛选。",
    ),
    "get_topology_context": (
        s.TopologyQuery,
        s.Topology,
        "device",
        "读取网络、供电、录像关系及历史关系时刻；节点状态仍为当前状态，拓扑只提供关联线索。",
    ),
    "query_work_orders": (
        s.QueryOrders,
        s.Page[s.Order],
        "workorder",
        "只读查询维修工单，不创建、不修改或关闭工单。",
    ),
    "get_maintenance_history": (
        s.MaintenanceQuery,
        s.Page[s.Maintenance],
        "workorder",
        "核查设备或指定工单的实际维修和独立验收记录。",
    ),
}


def visibility_hash(data):
    """Track the objects/counts actually disclosed, without requiring unchanged live heartbeat times."""

    def visible(value):
        if isinstance(value, dict):
            return {
                key: visible(item)
                for key, item in value.items()
                if key.endswith("Id")
                or key
                in {
                    "items",
                    "nodes",
                    "relations",
                    "anomalyLinks",
                    "deviceItems",
                    "total",
                    "completeness",
                    "unknownRelations",
                }
            }
        if isinstance(value, list):
            return [visible(item) for item in value]
        return value

    return fingerprint(visible(data))


def source_objects(data, arguments):
    """Business object IDs and versions accompany the query reference, never a fabricated source."""
    found = {}
    kinds = {
        "deviceId": "DEVICE",
        "sourceDeviceId": "DEVICE",
        "targetDeviceId": "DEVICE",
        "ipcDeviceId": "DEVICE",
        "incidentId": "INCIDENT",
        "anomalyId": "ANOMALY",
        "workOrderId": "WORK_ORDER",
        "monitoringPointId": "MONITORING_POINT",
    }

    def visit(value):
        if isinstance(value, dict):
            for key, item in value.items():
                if key in kinds and isinstance(item, str) and item:
                    found[(kinds[key], item)] = {
                        "sourceType": kinds[key],
                        "sourceId": item,
                        "sourceVersion": value.get("version"),
                        "stateVersion": value.get("stateVersion"),
                        "occurredAt": value.get(
                            "updatedAt", value.get("openedAt", value.get("createdAt"))
                        ),
                    }
                elif isinstance(item, (dict, list)):
                    visit(item)
        elif isinstance(value, list):
            for item in value:
                visit(item)

    visit(arguments)
    visit(data)
    return list(found.values())[:100]


def bounded_projection(value, limit=100):
    truncated = False

    def visit(item):
        nonlocal truncated
        if isinstance(item, str) and len(item) > 1000:
            truncated = True
            return item[:1000]
        if isinstance(item, list):
            if len(item) > limit:
                truncated = True
            return [visit(child) for child in item[:limit]]
        if isinstance(item, dict):
            return {key: visit(child) for key, child in item.items()}
        return item

    return visit(value), lambda: truncated


class ToolRunner:
    def __init__(self, settings, repository):
        self.settings, self.repository = settings, repository

    async def fetch(self, name, raw_arguments, principal, business, correlation):
        if name not in CONTRACTS:
            raise ServiceError("TOOL_NOT_ALLOWED", 403, "工具未开放")
        arguments = CONTRACTS[name][0].model_validate(raw_arguments)
        values = arguments.model_dump(mode="json", by_alias=True, exclude_none=True)
        if isinstance(arguments, s.TimeRange):
            values.pop("from", None)
            values.pop("to", None)
            values.update(arguments.bounded(self.settings.max_query_days))
        identity = None
        if name in {"get_device_detail", "get_device_status_history"}:
            identity = values.pop("deviceId")
        if name == "get_incident_detail":
            identity = values.pop("incidentId")
        if name == "get_maintenance_history":
            device, order = values.pop("deviceId", None), values.pop("workOrderId", None)
            if order:
                row = await business.read("_get_work_order", principal, correlation, identity=order)
                matches = not device or any(
                    item.get("deviceId") == device for item in row.get("deviceItems", [])
                )
                date = (
                    datetime.fromisoformat(row["createdAt"].replace("Z", "+00:00"))
                    if row.get("createdAt")
                    else None
                )
                if values.get("from") and (
                    date is None
                    or not datetime.fromisoformat(values["from"])
                    <= date
                    < datetime.fromisoformat(values["to"])
                ):
                    matches = False
                rows = [row] if matches and values["page"] == 1 else []
                raw = {
                    "items": rows,
                    "total": 1 if matches else 0,
                    "page": values["page"],
                    "size": values["size"],
                }
            else:
                raw = await business.read(
                    name, principal, correlation, identity=device, filters=values
                )
        else:
            raw = await business.read(
                name, principal, correlation, identity=identity, filters=values
            )
        # Validate the typed projection before it enters model context. Raw payload fields are dropped.
        clipped, truncated = bounded_projection(raw)
        projected = CONTRACTS[name][1].model_validate(clipped).model_dump(mode="json")
        cut = truncated()
        if len(json.dumps(projected, ensure_ascii=False)) > self.settings.max_tool_result_chars:
            key = "items" if "items" in projected else "nodes" if "nodes" in projected else None
            if key:
                while (
                    projected[key]
                    and len(json.dumps(projected, ensure_ascii=False))
                    > self.settings.max_tool_result_chars
                ):
                    projected[key].pop()
                if key == "nodes":
                    ids = {node["deviceId"] for node in projected["nodes"]}
                    projected["relations"] = [
                        r
                        for r in projected["relations"]
                        if r["sourceDeviceId"] in ids and r["targetDeviceId"] in ids
                    ]
                cut = True
            if len(json.dumps(projected, ensure_ascii=False)) > self.settings.max_tool_result_chars:
                raise ServiceError("TOOL_RESULT_TOO_LARGE", 502, "查询结果过大，请缩小范围")
        completeness = projected.get("completeness", "COMPLETE")
        if cut or completeness not in {"COMPLETE", "INCOMPLETE"}:
            completeness = "INCOMPLETE" if cut else "UNKNOWN"
        nodes = projected.get("items", projected.get("nodes", [projected]))
        if any(row.get("healthAvailability") not in {None, "AVAILABLE"} for row in nodes):
            completeness = "INCOMPLETE"
        freshness = "UNKNOWN"
        if any(row.get("dataFreshness") in {"STALE", "NO_DATA"} for row in nodes):
            freshness = "INSUFFICIENT"
        elif nodes and all(row.get("dataFreshness") == "FRESH" for row in nodes):
            freshness = "FRESH"
        effective = arguments.model_dump(mode="json", by_alias=True, exclude_none=True)
        if isinstance(arguments, s.TimeRange):
            effective.update({key: value for key, value in values.items() if key in {"from", "to"}})
        return projected, completeness, freshness, cut, effective

    async def execute(self, name, raw_arguments, deps):
        if name not in CONTRACTS:
            raise ServiceError("TOOL_NOT_ALLOWED", 403, "工具未开放")
        try:
            validated = CONTRACTS[name][0].model_validate(raw_arguments)
            safe_arguments = validated.model_dump(mode="json", by_alias=True, exclude_none=True)
        except ValidationError:
            safe_arguments = {}  # Invalid caller payloads never enter persistent summaries or logs.
        call_id = await run_in_threadpool(
            self.repository.start_tool,
            deps.principal.user_id,
            deps.run_id,
            name,
            safe_arguments,
            self.settings.max_tool_calls,
        )
        await deps.emit("tool.started", {"toolCallId": call_id, "toolName": name})
        start, provenance, digest = time.monotonic(), None, None
        try:
            async with asyncio.timeout(self.settings.tool_timeout_seconds):
                data, completeness, freshness, cut, effective = await self.fetch(
                    name, raw_arguments, deps.principal, deps.business, deps.correlation_id
                )
            empty = "items" in data and not data["items"] and not cut
            evidence_id = str(uuid.uuid4())
            reference = s.EvidenceView(
                evidenceId=evidence_id,
                sourceContext=CONTRACTS[name][2],
                sourceId=call_id,
                retrievedAt=datetime.now(UTC).isoformat(),
                sourceVersion=data.get("version"),
                freshnessStatus=freshness,
                summary=f"{name}：仅当前授权范围；完整性 {completeness}；节点状态为当前查询结果",
                sourceObjects=source_objects(data, effective),
            )
            result = s.ToolResult[dict](
                toolName=name,
                status="EMPTY" if empty else "SUCCEEDED",
                data=data,
                evidenceReferences=[reference],
                completeness=completeness,
                freshnessStatus=freshness,
                truncated=cut,
            )
            provenance = {
                "tool": name,
                "arguments": effective,
                "authorizationVersion": deps.principal.authorization_version,
                "sourceObjects": reference.sourceObjects,
            }
            digest = visibility_hash(data)
            if data.get("rootCauseStatus") == "CONFIRMED":
                deps.confirmed_root_evidence.add(evidence_id)
        except (ValidationError, ValueError):
            result = s.ToolResult(
                toolName=name,
                status="REJECTED",
                authorization="DENIED",
                errorCode="INVALID_ARGUMENT_OR_RESPONSE",
                safeMessage="参数或上游数据不符合工具契约，请核查后重试",
            )
        except (TimeoutError, ServiceError) as error:
            code = error.code if isinstance(error, ServiceError) else "TOOL_TIMEOUT"
            denied = isinstance(error, ServiceError) and error.status in {401, 403, 404}
            if isinstance(error, ServiceError) and error.status == 401:
                await run_in_threadpool(
                    self.repository.finish_tool,
                    deps.principal.user_id,
                    deps.run_id,
                    call_id,
                    s.ToolResult(
                        toolName=name, status="REJECTED", authorization="DENIED", errorCode=code
                    ),
                    None,
                    None,
                    round((time.monotonic() - start) * 1000),
                )
                raise
            result = s.ToolResult(
                toolName=name,
                status="REJECTED"
                if denied
                else "TIMED_OUT"
                if code in {"TOOL_TIMEOUT", "UPSTREAM_TIMEOUT"}
                else "FAILED",
                authorization="DENIED" if denied else "CURRENT_USER_SCOPE",
                errorCode=code,
                safeMessage="查询不可用或无权限；不能据此判断没有异常",
            )
        duration = round((time.monotonic() - start) * 1000)
        await run_in_threadpool(
            self.repository.finish_tool,
            deps.principal.user_id,
            deps.run_id,
            call_id,
            result,
            provenance,
            digest,
            duration,
        )
        for reference in result.evidenceReferences:
            deps.evidence_ids.add(reference.evidenceId)
        if result.status in {"FAILED", "REJECTED", "TIMED_OUT"}:
            deps.incomplete = True
        LOG.info(
            "tool_completed correlation=%s run=%s tool_call=%s name=%s status=%s latency_ms=%s",
            deps.correlation_id,
            deps.run_id,
            call_id,
            name,
            result.status,
            duration,
        )
        await deps.emit(
            "tool.completed",
            {
                "toolCallId": call_id,
                "toolName": name,
                "status": result.status,
                "durationMs": duration,
                "evidenceCount": len(result.evidenceReferences),
            },
        )
        return result.model_dump(mode="json")

    def tools(self):
        def create(name, contract):
            async def invoke(ctx, **arguments):
                return await self.execute(name, arguments, ctx.deps)

            return Tool.from_schema(
                invoke,
                name=name,
                description=contract[3],
                json_schema=contract[0].model_json_schema(by_alias=True),
                takes_ctx=True,
                sequential=True,
            )

        return tuple(create(name, contract) for name, contract in CONTRACTS.items())

    async def revalidate(self, evidence, principal, business, correlation):
        if not evidence or len(evidence) > self.settings.max_tool_calls:
            return False

        async def check(item):
            if (
                item["authorizationVersion"] != principal.authorization_version
                or item["toolName"] not in CONTRACTS
            ):
                return False
            try:
                async with asyncio.timeout(self.settings.tool_timeout_seconds):
                    data, *_ = await self.fetch(
                        item["toolName"], item["arguments"], principal, business, correlation
                    )
                return visibility_hash(data) == item["resultHash"]
            except (ServiceError, ValidationError, ValueError, TimeoutError):
                return False

        # Bound current permission rechecks, including old queries that covered multiple resources.
        for item in evidence:
            if not await check(item):
                return False
        return True
