"""Real user identity, SQL persistence, Java business tools and SSE; only the model is a fixture."""
import json
import os
import subprocess
import time
import uuid
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]


def main():
    config = dict(line.split("=", 1) for line in (ROOT/".env").read_text().splitlines() if "=" in line and not line.startswith("#"))
    token, sessions = None, []
    def request(path, body=None, key=None, stream=False, expected=(200,)):
        headers = {"Content-Type": "application/json", "X-Correlation-Id": str(uuid.uuid4())}
        if token:
            headers["Authorization"] = "Bearer "+token
        if key:
            headers["Idempotency-Key"] = key
        if stream:
            headers["Accept"] = "text/event-stream"
        command = Request("http://127.0.0.1:8080/api/v1"+path, headers=headers, data=None if body is None else json.dumps(body).encode())
        try:
            response = urlopen(command, timeout=90)
        except HTTPError as error:
            response = error
        if response.status not in expected:
            response.close()
            raise AssertionError(f"Unexpected HTTP {response.status} for {path}")
        return response
    def call(path, body=None, **kwargs):
        with request(path, body, **kwargs) as response:
            return json.load(response).get("data")
    def login(name):
        nonlocal token
        token = None
        token = call("/auth/login", {"loginName": name, "password": config["DEMO_ADMIN_PASSWORD" if name in {"admin","supervisor"} else "DEMO_OPERATOR_PASSWORD"]})["accessToken"]
        sessions.append(token)
        return token
    def events(response):
        for line in response:
            if line.startswith(b"data: "):
                yield json.loads(line[6:])
    try:
        supervisor = login("supervisor")
        deadline = time.monotonic()+120
        while not call("/incidents?status=PENDING_REVIEW")["items"]:
            if time.monotonic() > deadline:
                raise AssertionError("Simulator did not produce the expected incident")
            time.sleep(.5)
        conversation = call("/agent/conversations", {"title": "多台摄像机离线查询"}, expected=(201,))["conversationId"]
        path, key = "/agent/conversations/"+conversation+"/messages", str(uuid.uuid4())
        with request(path, {"content": "A 区为什么多台摄像头离线？请查询证据并区分事实和推断。"}, key=key, stream=True) as response:
            result = list(events(response))
        if not result or result[-1].get("status") != "SUCCEEDED":
            raise AssertionError("Query Run did not succeed; inspect local Agent logs")
        run = result[0]["runId"]
        if [item["seq"] for item in result] != list(range(1,len(result)+1)):
            raise AssertionError("SSE event order is not monotonic")
        view = call("/agent/runs/"+run)
        if view["toolCallCount"] != 9 or view["contentStatus"] != "VISIBLE" or not view["answer"]["facts"]:
            raise AssertionError("Nine real tool calls or persisted answer evidence are missing")
        names = {item["toolName"] for item in view["evidenceReferences"]}
        if len(names) != 9 or "prepare_work_order" in names:
            raise AssertionError("Read-only allowlist was not preserved")
        replay = call(path, {"content": "A 区为什么多台摄像头离线？请查询证据并区分事实和推断。"}, key=key, stream=True)
        if replay["runId"] != run:
            raise AssertionError("Idempotent replay created a different Run")
        if call("/workorders")["total"]:
            raise AssertionError("A read-only query created business work")
        login("operator")
        call("/agent/runs/"+run, expected=(404,))
        token = supervisor
        # Real network disconnect: keep only run.started, then close the POST response.
        second = call("/agent/conversations", {"title": "断连验证"}, expected=(201,))["conversationId"]
        with request("/agent/conversations/"+second+"/messages", {"content": "slow-fixture 请查询"}, key=str(uuid.uuid4()), stream=True) as response:
            cancelled = next(events(response))["runId"]
        deadline = time.monotonic()+15
        while call("/agent/runs/"+cancelled)["status"] != "CANCELLED":
            if time.monotonic() > deadline:
                raise AssertionError("Disconnected client did not cancel its Run")
            time.sleep(.25)
        # Restart preserves the final result; history is still reauthorized through current Core APIs.
        subprocess.run(["docker","compose","-f","compose.yml","-f","infra/compose.agent-test.yml","--profile","agent","restart","agent-service"],cwd=ROOT,env=os.environ,
                       check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        deadline = time.monotonic()+45
        while True:
            try:
                if call("/agent/runs/"+run)["status"] == "SUCCEEDED":
                    break
            except OSError:
                pass
            if time.monotonic() > deadline:
                raise AssertionError("Run was not restored after restart")
            time.sleep(.5)
        # Revoke the owner's current grant and sign in anew: historical evidence must no longer be exposed.
        me = call("/auth/me")
        login("admin")
        grant = me["scopeSummaries"][0]["grantId"]
        # A whole grant removal makes agent.use unavailable: current request must be forbidden or unauthenticated.
        call("/grants/"+grant+"/revoke", {"reason":"离线验收撤销授权"}, key=str(uuid.uuid4()))
        token = supervisor
        call("/agent/runs/"+run, expected=(401,403))
        print("Agent query checks passed: nine live Core tools, evidence, candidate-only analysis, read-only business, persisted answer, replay, scope isolation, disconnect cancellation and restart.")
    finally:
        for saved in sessions:
            token = saved
            call("/auth/logout", {}, expected=(200,401))


if __name__ == "__main__":
    main()
