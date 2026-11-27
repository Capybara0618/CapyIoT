"""Real business services, Agent, MySQL and Dispatcher; only model generation uses the offline fixture."""

import json
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from smoke_context import active_outage_incident, api_base, credentials, tls_context

ROOT = Path(__file__).resolve().parents[1]


def main():
    config = credentials()
    tokens, token = [], None

    def request(path, body=None, key=None, expected=(200,), stream=False):
        headers = {
            "Content-Type": "application/json",
            "X-Correlation-Id": uuid.uuid4().hex,
        }
        if token:
            headers["Authorization"] = "Bearer " + token
        if key:
            headers["Idempotency-Key"] = key
        if stream:
            headers["Accept"] = "text/event-stream"
        req = Request(
            api_base() + path,
            headers=headers,
            data=None if body is None else json.dumps(body).encode(),
        )
        try:
            response = urlopen(req, timeout=90, context=tls_context())
        except HTTPError as error:
            response = error
        if response.status not in expected:
            status = response.status
            code = json.load(response).get("code", "UNKNOWN")
            response.close()
            raise AssertionError(f"Unexpected HTTP {status} for {path} ({code})")
        return response

    def call(path, body=None, **kwargs):
        with request(path, body, **kwargs) as response:
            return json.load(response).get("data")

    def login(name):
        nonlocal token
        token = None
        result = call(
            "/auth/login",
            {
                "loginName": name,
                "password": config[
                    "DEMO_ADMIN_PASSWORD"
                    if name in {"admin", "supervisor"}
                    else "DEMO_OPERATOR_PASSWORD"
                ],
            },
        )
        token = result["accessToken"]
        tokens.append(token)
        return token

    def generate(content):
        conversation = call(
            "/agent/conversations", {"title": "工单确认验收"}, expected=(201,)
        )
        with request(
            "/agent/conversations/" + conversation["conversationId"] + "/messages",
            {"content": content},
            key=uuid.uuid4().hex,
            stream=True,
        ) as response:
            chunks = response.read().decode().split("\n\n")
        events = [
            (
                next(
                    (
                        line[7:]
                        for line in part.splitlines()
                        if line.startswith("event: ")
                    ),
                    "",
                ),
                json.loads(
                    next(
                        line[6:]
                        for line in part.splitlines()
                        if line.startswith("data: ")
                    )
                ),
            )
            for part in chunks
            if "data: " in part
        ]
        assert (
            events[-1][0] == "run.completed"
            and events[-1][1]["status"] == "WAITING_CONFIRMATION"
        ), "Draft generation did not wait for explicit confirmation"
        ready = next(data for event, data in events if event == "draft.ready")
        draft = call("/agent/drafts/" + ready["draftId"])
        assert (
            draft["revision"] == ready["draftRevision"]
            and draft["contentHash"] == ready["contentHash"]
        )
        run = call("/agent/runs/" + events[0][1]["runId"])
        assert (
            run["draftId"] == draft["draftId"]
            and run["executionStatus"] == "NOT_EXECUTED"
        )
        return draft

    def decision(draft, action, **kwargs):
        return call(
            "/agent/drafts/" + draft["draftId"] + "/" + action,
            {"draftRevision": draft["revision"], "contentHash": draft["contentHash"]},
            key="decision-" + draft["draftId"] + action,
            **kwargs,
        )

    try:
        supervisor = login("supervisor")
        actor = call("/auth/me")["userId"]
        before = call("/workorders")["total"]
        draft = generate(
            "draft-fixture MANUAL 请为可见的模拟视频设备生成维修工单草稿，等待我确认。"
        )
        assert call("/workorders")["total"] == before
        login("operator")
        call("/agent/drafts/" + draft["draftId"], expected=(404,))
        decision(draft, "confirm", expected=(404,))
        token = supervisor
        assert decision(draft, "reject")["status"] == "REJECTED"
        assert decision(draft, "reject")["execution"] is None
        decision(draft, "confirm", expected=(409,))
        assert call("/workorders")["total"] == before

        deadline = time.monotonic() + 120
        while not (pending := active_outage_incident(call)):
            if time.monotonic() >= deadline:
                raise AssertionError(
                    "The current switch outage did not produce an active candidate"
                )
            time.sleep(0.5)
        call(
            "/incidents/" + pending["incidentId"] + "/review",
            {
                "decision": "CONFIRM",
                "reason": "阶段四模拟故障人工研判，准备维修",
                "expectedVersion": pending["version"],
            },
            key=uuid.uuid4().hex,
        )
        draft = generate(
            "draft-fixture INCIDENT "
            + pending["incidentId"]
            + " 请生成该故障的维修工单草稿，等待我确认。"
        )
        assert (
            len(draft["payload"]["deviceItems"]) >= 2
            and draft["relatedEvidenceReferences"]
        )
        assert call("/workorders")["total"] == before
        call(
            "/agent/drafts/" + draft["draftId"] + "/confirm",
            {
                "draftRevision": draft["revision"] + 1,
                "contentHash": draft["contentHash"],
            },
            key=uuid.uuid4().hex,
            expected=(409,),
        )
        with ThreadPoolExecutor(max_workers=4) as pool:
            confirmations = list(
                pool.map(
                    lambda _: decision(draft, "confirm", expected=(202,)), range(4)
                )
            )
        assert len({item["execution"]["executionId"] for item in confirmations}) == 1
        deadline = time.monotonic() + 45
        while True:
            result = call("/agent/drafts/" + draft["draftId"])
            if result["execution"]["status"] in {"SUCCEEDED", "FAILED"}:
                break
            if time.monotonic() > deadline:
                raise AssertionError("Dispatcher did not reach a definite result")
            time.sleep(0.5)
        assert result["status"] == "EXECUTED", (
            f"Unexpected dispatcher outcome: {result['execution']['errorCode']}"
        )
        created = call("/workorders/" + result["execution"]["workOrderId"])
        assert (
            created["createdBy"] == actor
            and len(created["deviceItems"]) == 2
            and created["incidentId"] == pending["incidentId"]
        )
        assert call("/workorders")["total"] == before + 1
        repeated = decision(draft, "confirm", expected=(202,))
        assert repeated["execution"]["workOrderId"] == created["workOrderId"]
        assert call("/workorders")["total"] == before + 1
        run = call("/agent/runs/" + draft["runId"])
        assert run["executionStatus"] == "SUCCEEDED"
        print(
            "Draft checks passed: model draft tool, SSE draft.ready, evidence, owner isolation, rejection, revision binding, concurrent confirmation, real delegated Java creation, multi-device Incident work order and repeat-confirmation idempotency."
        )
    finally:
        for saved in tokens:
            token = saved
            call("/auth/logout", {}, expected=(200, 401))


if __name__ == "__main__":
    main()
