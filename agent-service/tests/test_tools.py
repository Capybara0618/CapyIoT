import asyncio
import json
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta

import httpx
import pytest
from pydantic import ValidationError

from app.agent import AgentDeps
from app.core_client import CoreApiClient, Principal
from app.errors import ServiceError
from app.tool_schemas import MaintenanceQuery, QueryDevices, StatusHistory, TopologyQuery
from app.tools import CONTRACTS, ToolRunner

DEVICE = {
    "deviceId": "ipc-one",
    "name": "入口摄像机",
    "deviceType": "IPC",
    "dataFreshness": "STALE",
    "version": 1,
}
INCIDENT = {
    "incidentId": "incident-one",
    "incidentNo": "IN-001",
    "title": "下联设备离线",
    "status": "PENDING_REVIEW",
    "priority": "P2",
    "rootCauseStatus": "UNCONFIRMED",
    "version": 1,
}
ORDER = {
    "workOrderId": "1",
    "workOrderNo": "WO-1",
    "title": "核查",
    "status": "CLOSED",
    "priority": "P3",
    "sourceType": "MANUAL",
    "version": 1,
    "createdAt": "2026-11-01T00:00:00Z",
    "deviceItems": [{"deviceId": "ipc-one", "deviceRole": "MAIN", "issueDescription": "核查"}],
    "repairRecords": [{"recordId": "record-one", "recordType": "RESULT", "content": "恢复"}],
}


def page(row):
    return {"items": [row], "total": 1, "page": 1, "size": 20}


CASES = {
    "query_devices": ({}, page(DEVICE)),
    "get_device_detail": ({"deviceId": "ipc-one"}, DEVICE),
    "get_device_status_history": (
        {"deviceId": "ipc-one"},
        page(
            {
                "historyId": 1,
                "stateDimension": "CONNECTIVITY",
                "newValue": "OFFLINE",
                "stateVersion": 1,
            }
        ),
    ),
    "query_anomalies": (
        {"severity": "FAULT"},
        page(
            {
                "anomalyId": "anomaly-one",
                "anomalyType": "DEVICE_CONNECTION_LOST",
                "status": "ACTIVE",
                "severity": "FAULT",
                "version": 1,
            }
        ),
    ),
    "query_incidents": ({}, page(INCIDENT)),
    "get_incident_detail": ({"incidentId": "incident-one"}, INCIDENT),
    "get_topology_context": (
        {"deviceId": "ipc-one"},
        {
            "targetId": "ipc-one",
            "asOf": "2026-11-01T00:00:00Z",
            "nodes": [DEVICE],
            "relations": [],
            "completeness": "INCOMPLETE",
        },
    ),
    "query_work_orders": ({}, page(ORDER)),
    "get_maintenance_history": ({"deviceId": "ipc-one"}, page(ORDER)),
}


@dataclass
class ToolRepository:
    started: list = field(default_factory=list)
    finished: list = field(default_factory=list)

    def start_tool(self, owner, run, name, args, budget):
        if len(self.started) >= budget:
            raise ServiceError("RUN_BUDGET_EXCEEDED", 409, "budget")
        self.started.append((name, args))
        return "call-" + str(len(self.started))

    def finish_tool(self, *args):
        self.finished.append(args)


def principal(permissions=None):
    return Principal(
        "user-one",
        "session-one",
        1,
        frozenset(
            permissions
            or {
                "agent.use",
                "device.read",
                "anomaly.read",
                "incident.read",
                "topology.read",
                "workorder.read",
            }
        ),
        "private-token",
    )


@pytest.mark.parametrize("name", list(CASES))
async def test_each_tool_projects_and_records_current_authorized_evidence(settings, name):
    args, data = CASES[name]
    requests = []

    def transport(request):
        requests.append(request)
        assert request.headers["Authorization"] == "Bearer private-token"
        return httpx.Response(
            200,
            json={
                "code": "OK",
                "data": {
                    **data,
                    "deviceSecret": "DO-NOT-EXPOSE",
                    "payload": {"raw": "DO-NOT-EXPOSE"},
                },
            },
        )

    repo = ToolRepository()
    async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as http:
        runner = ToolRunner(settings, repo)
        deps = AgentDeps(principal(), CoreApiClient(http, settings.core_base_url), "run", "trace")
        result = await runner.execute(name, args, deps)
    assert result["status"] == "SUCCEEDED" and result["evidenceReferences"]
    assert len(deps.evidence_ids) == 1 and repo.finished
    assert "DO-NOT-EXPOSE" not in json.dumps(result)
    assert requests[0].method == "GET"
    assert all("private-token" not in json.dumps(record) for record in repo.started)


@pytest.mark.parametrize("name", list(CASES))
async def test_each_tool_rejects_permission_or_missing_resource_without_leaking(settings, name):
    repo = ToolRepository()
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(
            lambda _: httpx.Response(403, json={"message": "private diagnostic"})
        )
    ) as http:
        deps = AgentDeps(
            principal({"agent.use"}), CoreApiClient(http, settings.core_base_url), "run", "trace"
        )
        result = await ToolRunner(settings, repo).execute(name, CASES[name][0], deps)
        assert result["status"] == "REJECTED" and result["data"] is None
        assert not deps.evidence_ids and deps.incomplete


@pytest.mark.parametrize("name", list(CASES))
async def test_each_tool_normalizes_upstream_outage_without_claiming_empty(settings, name):
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(lambda _: httpx.Response(503))
    ) as http:
        deps = AgentDeps(principal(), CoreApiClient(http, settings.core_base_url), "run", "trace")
        result = await ToolRunner(settings, ToolRepository()).execute(name, CASES[name][0], deps)
        assert result["status"] == "FAILED" and result["data"] is None


@pytest.mark.parametrize(
    "name",
    [
        "query_devices",
        "query_anomalies",
        "query_incidents",
        "query_work_orders",
        "get_device_status_history",
        "get_maintenance_history",
    ],
)
async def test_empty_is_limited_to_current_scope_with_evidence(settings, name):
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(
            lambda _: httpx.Response(
                200, json={"code": "OK", "data": {"items": [], "total": 0, "page": 1, "size": 20}}
            )
        )
    ) as http:
        result = await ToolRunner(settings, ToolRepository()).execute(
            name,
            CASES[name][0],
            AgentDeps(principal(), CoreApiClient(http, settings.core_base_url), "run", "trace"),
        )
        assert result["status"] == "EMPTY" and result["authorization"] == "CURRENT_USER_SCOPE"
        assert result["evidenceReferences"]


def test_inputs_forbid_identity_injection_and_bound_times_and_topology():
    for value in ({"parkId": "other"}, {"userId": "admin"}, {"size": 51}, {"page": 0}):
        with pytest.raises(ValidationError):
            QueryDevices.model_validate(value)
    with pytest.raises(ValidationError):
        TopologyQuery()
    with pytest.raises(ValidationError):
        TopologyQuery(deviceId="ipc", depth=4)
    with pytest.raises(ValidationError):
        MaintenanceQuery()
    start = datetime.now(UTC) - timedelta(days=2)
    time_range = StatusHistory(deviceId="ipc", **{"from": start.isoformat()})
    assert set(time_range.bounded(31)) == {"from", "to"}
    with pytest.raises(ValidationError):
        StatusHistory(deviceId="ipc", **{"from": "2026-01-01T00:00:00+08:00"})
    with pytest.raises(ValueError):
        StatusHistory(
            deviceId="ipc", **{"from": "2025-01-01T00:00:00Z", "to": "2026-01-01T00:00:00Z"}
        ).bounded(31)


async def test_topology_truncation_and_stale_data_are_explicit(settings):
    data = {
        "targetId": "ipc",
        "asOf": "2026-11-01T00:00:00Z",
        "nodes": [{**DEVICE, "deviceId": "ipc-" + str(i)} for i in range(120)],
        "relations": [],
        "completeness": "COMPLETE",
    }
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(
            lambda _: httpx.Response(200, json={"code": "OK", "data": data})
        )
    ) as http:
        runner = ToolRunner(settings, ToolRepository())
        result = await runner.execute(
            "get_topology_context",
            {"deviceId": "ipc"},
            AgentDeps(principal(), CoreApiClient(http, settings.core_base_url), "run", "trace"),
        )
        assert result["truncated"] and result["completeness"] == "INCOMPLETE"
        assert len(result["data"]["nodes"]) <= 100 and result["freshnessStatus"] == "INSUFFICIENT"


async def test_history_evidence_rechecks_current_scope_and_authorization_version(settings):
    data = page(DEVICE)
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(
            lambda _: httpx.Response(200, json={"code": "OK", "data": data})
        )
    ) as http:
        repo, core = ToolRepository(), CoreApiClient(http, settings.core_base_url)
        runner = ToolRunner(settings, repo)
        await runner.execute("query_devices", {}, AgentDeps(principal(), core, "run", "trace"))
        provenance, digest = repo.finished[-1][4:6]
        evidence = [
            {
                "toolName": provenance["tool"],
                "arguments": provenance["arguments"],
                "authorizationVersion": 1,
                "resultHash": digest,
            }
        ]
        assert await runner.revalidate(evidence, principal(), core, "trace")
        data["items"] = []
        data["total"] = 0
        assert not await runner.revalidate(evidence, principal(), core, "trace")
        evidence[0]["authorizationVersion"] = 0
        assert not await runner.revalidate(evidence, principal(), core, "trace")


async def test_tool_deadline_preserves_failed_record(settings):
    async def transport(_):
        await asyncio.sleep(10)

    async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as http:
        repo = ToolRepository()
        runner = ToolRunner(settings.model_copy(update={"tool_timeout_seconds": 0.01}), repo)
        result = await runner.execute(
            "query_devices",
            {},
            AgentDeps(principal(), CoreApiClient(http, settings.core_base_url), "run", "trace"),
        )
        assert result["status"] == "TIMED_OUT" and repo.finished


def test_only_nine_read_tools_are_registered(settings):
    tools = ToolRunner(settings, ToolRepository()).tools()
    assert {tool.name for tool in tools} == set(CONTRACTS) and len(tools) == 9
    assert "prepare_work_order" not in {tool.name for tool in tools}


@pytest.mark.parametrize(
    "kind,description",
    [("NETWORK", "上游交换机"), ("POWER", "受电 IPC"), ("VIDEO", "不是网络上下游")],
)
def test_relation_direction_is_derived_from_core_relation_type(kind, description):
    from app.tool_schemas import Relation

    relation = Relation.model_validate(
        {
            "relationId": "edge-one",
            "relationType": kind,
            "sourceDeviceId": "source",
            "targetDeviceId": "target",
            "direction": "IGNORE SCHEMA",
        }
    )
    output = relation.model_dump(mode="json")
    assert output["sourceDeviceId"] == "source" and output["targetDeviceId"] == "target"
    assert description in output["direction"] and "IGNORE SCHEMA" not in output["direction"]


def test_offline_model_budget_notice_does_not_reset_completed_query_sequence():
    from io import BytesIO
    from types import SimpleNamespace

    from model_fixture import Handler

    names = [
        "query_devices",
        "query_incidents",
        "get_device_detail",
        "get_incident_detail",
        "get_topology_context",
        "get_device_status_history",
        "query_anomalies",
    ]
    messages = [{"role": "user", "content": "A 区为什么多台摄像机离线"}]
    for name in names:
        messages.append(
            {"role": "tool", "content": json.dumps({"toolName": name, "data": {"items": []}})}
        )
    messages.append({"role": "user", "content": "运行剩余预算：模型响应 3 次，请及时总结"})
    body = json.dumps({"messages": messages, "tools": []}).encode()
    output = BytesIO()
    request = SimpleNamespace(
        headers={"Content-Length": str(len(body))},
        rfile=BytesIO(body),
        wfile=output,
        send_response=lambda *_: None,
        send_header=lambda *_: None,
        end_headers=lambda: None,
    )
    Handler.do_POST(request)
    response = json.loads(output.getvalue())
    assert (
        response["choices"][0]["message"]["tool_calls"][0]["function"]["name"]
        == "query_work_orders"
    )
