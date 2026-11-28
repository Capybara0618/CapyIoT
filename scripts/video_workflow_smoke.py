"""Verify the simulated outage-to-independent-acceptance flow over real HTTPS APIs.

Run only after the isolated stack and switch-outage simulator scenario complete.
"""

import argparse
import json
import ssl
import time
import uuid
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from smoke_context import active_outage_incident

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="https://localhost:8443")
    parser.add_argument("--ca-file", type=Path, default=ROOT / "var/tls/ca.pem")
    parser.add_argument("--env-file", type=Path, default=ROOT / ".env")
    parser.add_argument("--evidence-dir", type=Path)
    parser.add_argument(
        "--agent",
        action="store_true",
        help="Create through an explicitly confirmed Agent draft",
    )
    parser.add_argument(
        "--real-model",
        action="store_true",
        help="Use a normal user prompt rather than the offline fixture marker",
    )
    parser.add_argument(
        "--resume-draft",
        help="Resume only the original confirmed draft after a transport or visibility failure",
    )
    args = parser.parse_args()
    if args.resume_draft and not args.agent:
        parser.error("--resume-draft requires --agent")
    tls = ssl.create_default_context(cafile=args.ca_file)
    config = dict(
        line.split("=", 1)
        for line in args.env_file.read_text().splitlines()
        if "=" in line and not line.startswith("#")
    )
    token, sessions = None, []

    def call(path, body=None, method=None, expected=(200,), key=None, stream=False):
        headers = {
            "Content-Type": "application/json",
            "X-Request-Id": str(uuid.uuid4()),
            "X-Correlation-Id": correlation,
        }
        if token:
            headers["Authorization"] = "Bearer " + token
        if body is not None:
            headers["Idempotency-Key"] = key or str(uuid.uuid4())
        if stream:
            headers["Accept"] = "text/event-stream"
        request = Request(
            args.base_url.rstrip("/") + "/api/v1" + path,
            headers=headers,
            method=method,
            data=None if body is None else json.dumps(body).encode(),
        )
        try:
            response = urlopen(request, timeout=90 if stream else 15, context=tls)
        except HTTPError as error:
            response = error
        with response:
            if response.status not in expected:
                code = json.load(response).get("code", "UNKNOWN")
                raise AssertionError(
                    f"{method or ('POST' if body is not None else 'GET')} {path} returned HTTP {response.status} ({code})"
                )
            return response.read().decode() if stream else json.load(response)

    def login(name):
        nonlocal token
        token = None
        token = call(
            "/auth/login",
            {
                "loginName": name,
                "password": config[
                    "DEMO_ADMIN_PASSWORD"
                    if name == "supervisor"
                    else "DEMO_OPERATOR_PASSWORD"
                ],
            },
        )["data"]["accessToken"]
        sessions.append(token)
        return call("/auth/me")["data"]

    def wait(check, description):
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            result = check()
            if result:
                return result
            time.sleep(0.5)
        raise AssertionError("Timed out: " + description)

    correlation = str(uuid.uuid4())
    try:
        login("supervisor")
        catalog = call("/openapi")["data"]["services"]
        expected = {
            "device-service",
            "alarm-service",
            "workflow-service",
            "platform-service",
            "agent-service",
        }
        if {item["service"] for item in catalog} != expected:
            raise AssertionError("Independent service API catalog is incomplete")
        if args.resume_draft:
            resumed = call("/agent/drafts/" + args.resume_draft)["data"]
            if resumed["execution"] is None or not resumed["payload"].get("incidentId"):
                raise AssertionError(
                    "Resume requires the original confirmed Incident draft"
                )
            candidate = {"incidentId": resumed["payload"]["incidentId"]}
        else:
            candidate = wait(
                lambda: active_outage_incident(lambda path: call(path)["data"]),
                "simulated topology correlation",
            )
        item = call("/incidents/" + candidate["incidentId"])["data"]
        incident_id = item["incidentId"]
        if item["rootCauseStatus"] != "UNCONFIRMED" or (
            item["workOrders"] and not args.resume_draft
        ):
            raise AssertionError(
                "Correlation must neither confirm a root cause nor automatically create work"
            )
        device_ids = sorted(set(link["deviceId"] for link in item["anomalyLinks"]))
        if len(device_ids) < 3:
            raise AssertionError(
                "Expected the switch and at least two downstream devices"
            )
        if not args.resume_draft:
            call(
                "/incidents/" + incident_id + "/review",
                {
                    "decision": "CONFIRM",
                    "reason": "统一验收：根据故障及拓扑证据确认需要维修",
                    "expectedVersion": item["version"],
                },
            )
        body = {
            "sourceType": "INCIDENT",
            "incidentId": incident_id,
            "title": "验证交换机与摄像机通信恢复",
            "description": "根据模拟故障证据逐项核查设备链路",
            "workCategory": "网络维修",
            "expectedResult": "各设备状态与录像数据可核查",
            "deviceItems": [
                {
                    "deviceId": d,
                    "deviceRole": "MAIN"
                    if d == item["candidateRootDeviceId"]
                    else "RELATED",
                    "issueDescription": "检查通信及业务状态",
                }
                for d in device_ids
            ],
        }
        if not any(d["deviceRole"] == "MAIN" for d in body["deviceItems"]):
            body["deviceItems"][0]["deviceRole"] = "MAIN"
        key = str(uuid.uuid4())
        if args.agent:
            before = call("/workorders")["data"]["total"]
            if args.resume_draft:
                draft = resumed
            else:
                conversation = call(
                    "/agent/conversations",
                    {"title": "完整故障维修验收"},
                    expected=(201,),
                )["data"]
                prompt = (
                    "主管已决定为故障 "
                    + incident_id
                    + " 安排维修，无需重新诊断根因。请读取该故障详情中的关联设备和证据，直接据此准备维修工单草稿，"
                    "无需扩展异常或拓扑检索。"
                    "必须使用 INCIDENT 来源并关联这个故障，等待我在页面确认，不得自动创建正式工单。"
                    if args.real_model
                    else "draft-fixture INCIDENT "
                    + incident_id
                    + " 请根据该故障生成维修草稿，等待人工确认。"
                )
                wire = call(
                    "/agent/conversations/"
                    + conversation["conversationId"]
                    + "/messages",
                    {"content": prompt},
                    stream=True,
                )
                if args.evidence_dir:
                    from microservices_common import private_path
                    destination = private_path(args.evidence_dir)
                    destination.mkdir(parents=True, exist_ok=True)
                    (destination / ("workflow-run-" + conversation["conversationId"] + ".sse")).write_text(wire, encoding="utf-8")
                ready = next(
                    (json.loads(line[6:])
                    for part in wire.split("\n\n")
                    if "event: draft.ready" in part
                    for line in part.splitlines()
                    if line.startswith("data: ")),
                    None,
                )
                if ready is None:
                    failed = [json.loads(line[6:]) for part in wire.split("\n\n") if "event: run.failed" in part
                              for line in part.splitlines() if line.startswith("data: ")]
                    raise AssertionError("Agent did not prepare a draft: " + (failed[-1].get("errorCode", "UNKNOWN") if failed else "NO_DRAFT"))
                draft = call("/agent/drafts/" + ready["draftId"])["data"]
            if call("/workorders")["data"]["total"] != before:
                raise AssertionError(
                    "The Agent created a formal order before confirmation"
                )
            decision = {
                "draftRevision": draft["revision"],
                "contentHash": draft["contentHash"],
            }
            accepted = call(
                "/agent/drafts/" + draft["draftId"] + "/confirm",
                decision,
                expected=(202,),
            )["data"]

            def executed():
                current = call("/agent/drafts/" + draft["draftId"])["data"]
                execution = current.get("execution") or {}
                if execution.get("status") == "FAILED":
                    raise AssertionError(
                        "Confirmed Agent execution failed: "
                        + str(execution.get("errorCode"))
                    )
                return current if execution.get("status") == "SUCCEEDED" else None

            finished = wait(executed, "confirmed Agent workflow receipt")
            created = call("/workorders/" + finished["execution"]["workOrderId"])[
                "data"
            ]
            repeated = call(
                "/agent/drafts/" + draft["draftId"] + "/confirm",
                decision,
                expected=(202,),
            )["data"]
            if repeated["execution"]["executionId"] != accepted["execution"][
                "executionId"
            ] or call("/workorders")["data"]["total"] != before + (
                0 if args.resume_draft else 1
            ):
                raise AssertionError(
                    "Repeated confirmation created a second execution or order"
                )
            if created["incidentId"] != incident_id:
                raise AssertionError("The confirmed draft lost its Incident binding")
            device_ids = [d["deviceId"] for d in created["deviceItems"]]
        else:
            created = call("/workorders", body, expected=(201,), key=key)["data"]
            replay = call("/workorders", body, expected=(200,), key=key)["data"]
            if created["workOrderId"] != replay["workOrderId"] or replay["created"]:
                raise AssertionError(
                    "Duplicate create did not return the original work order"
                )
        order_id = created["workOrderId"]
        current = call("/incidents/" + incident_id)["data"]
        call(
            "/incidents/" + incident_id + "/close",
            {
                "reason": "工单仍在处理中，不应允许关闭",
                "resolvedDisposition": "验证门禁",
                "expectedVersion": current["version"],
            },
            expected=(409,),
        )
        people = call("/workorder-assignees?deviceIds=" + ",".join(device_ids))["data"]
        repairer = next(p for p in people if p["roleCode"] == "REPAIR_TECH")
        acceptor = next(
            p
            for p in people
            if p["roleCode"] == "ACCEPTANCE_STAFF" and p["userId"] != repairer["userId"]
        )
        prefix = "/workorders/" + order_id
        call(
            prefix + "/assign",
            {
                "assignedRepairerId": repairer["userId"],
                "designatedAcceptorId": acceptor["userId"],
                "expectedVersion": 0,
            },
        )
        actor = login("repairer")
        if actor["userId"] != repairer["userId"]:
            raise AssertionError(
                "The isolated fixture must use the seeded repair account"
            )
        call(prefix + "/accept", {"expectedVersion": 1})
        call(prefix + "/start", {"expectedVersion": 2})
        call(
            prefix + "/repair-records",
            {
                "recordType": "DIAGNOSIS",
                "content": "核查交换机状态、下联设备心跳与录像通道",
                "evidenceReferenceIds": [],
                "expectedVersion": 3,
            },
        )
        call(
            prefix + "/submit-completion",
            {
                "completionSummary": "逐台核查模拟故障恢复后的设备状态",
                "deviceResults": [
                    {"deviceId": d, "result": "通信与业务观察已核查"}
                    for d in device_ids
                ],
                "expectedVersion": 4,
            },
        )
        call(
            prefix + "/acceptance",
            {
                "result": "PASS",
                "comment": "维修人不允许自验",
                "checkItems": [{"name": "职责分离", "passed": True}],
                "expectedVersion": 5,
            },
            expected=(403, 404),
        )
        actor = login("acceptor")
        if actor["userId"] != acceptor["userId"]:
            raise AssertionError(
                "The isolated fixture must use the seeded acceptance account"
            )
        call(
            prefix + "/acceptance",
            {
                "result": "PASS",
                "comment": "独立复核设备通信与业务观察",
                "checkItems": [
                    {"name": "逐台状态核查", "passed": True},
                    {"name": "预期结果核查", "passed": True},
                ],
                "expectedVersion": 5,
            },
        )
        login("supervisor")
        order = call(prefix)["data"]
        if order["status"] != "CLOSED" or len(order["acceptanceRecords"]) != 1:
            raise AssertionError(
                "Independent acceptance did not close exactly one work cycle"
            )
        item = wait(
            lambda: (lambda i: i if i["status"] == "PENDING_CLOSE" else None)(
                call("/incidents/" + incident_id)["data"]
            ),
            "closure readiness projection",
        )

        def close_when_facts_covered():
            current = call("/incidents/" + incident_id)["data"]
            result = call(
                "/incidents/" + incident_id + "/close",
                {
                    "resolvedDisposition": "异常恢复且工单独立验收通过",
                    "reason": "统一验收：复核无活动异常与未终结工单",
                    "expectedVersion": current["version"],
                },
                expected=(200, 409),
            )
            if result["code"] == "OK":
                return True
            if result["code"] not in {"FAULT_EVIDENCE_INCOMPLETE", "VERSION_CONFLICT"}:
                raise AssertionError("Unexpected close rejection: " + result["code"])
            return False

        wait(
            close_when_facts_covered,
            "authoritative observation coverage before closure",
        )
        history = call("/devices/" + device_ids[0] + "/maintenance-history")["data"]
        if not any(
            o["workOrderId"] == order_id
            and o["repairRecords"]
            and o["acceptanceRecords"]
            for o in history["items"]
        ):
            raise AssertionError(
                "Maintenance history omitted repair or acceptance evidence"
            )
        wait(
            lambda: any(
                a["resourceId"] == order_id and a["actionCode"] == "WorkOrderClosed"
                for a in call("/audit-records?size=100&resourceId=" + order_id)["data"][
                    "items"
                ]
            ),
            "domain audit projection",
        )
        print(
            "HTTPS workflow checks passed: candidate correlation, explicit creation, idempotency, Gate conflicts, separate repair/acceptance and final incident closure."
        )
        if args.agent:
            print(
                "The same complete lifecycle used an evidence-backed Agent draft and explicit confirmation; real model="
                + str(args.real_model)
            )
    finally:
        for saved in sessions:
            token = saved
            call("/auth/logout", {}, expected=(200, 401))


if __name__ == "__main__":
    main()
