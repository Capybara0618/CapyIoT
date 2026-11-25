"""Real Gateway/Core/Agent/MySQL checks; called only by explicit offline stage-two verification."""
import json
import os
import subprocess
import time
import uuid
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen


def main():
    root = Path(__file__).resolve().parents[1]
    config = dict(line.split("=", 1) for line in (root/".env").read_text().splitlines() if "=" in line and not line.startswith("#"))
    token = None
    sessions = []

    def call(path, body=None, expected=(200,)):
        headers = {"Content-Type": "application/json", "X-Correlation-Id": str(uuid.uuid4())}
        if token:
            headers["Authorization"] = "Bearer "+token
        request = Request("http://127.0.0.1:8080/api/v1"+path, headers=headers,
                          data=None if body is None else json.dumps(body).encode())
        try:
            response = urlopen(request, timeout=15)
        except HTTPError as error:
            response = error
        with response:
            if response.status not in expected:
                raise AssertionError(f"Unexpected HTTP {response.status} for {path}")
            data = json.load(response)
            return data.get("data")

    def login(name):
        nonlocal token
        token = None
        password = config["DEMO_ADMIN_PASSWORD" if name in {"admin","supervisor"} else "DEMO_OPERATOR_PASSWORD"]
        token = call("/auth/login", {"loginName": name, "password": password})["accessToken"]
        sessions.append(token)
        return token

    try:
        supervisor = login("supervisor")
        item = call("/agent/conversations", {"title": "阶段二持久化验收"}, expected=(201,))
        identity = item["conversationId"]
        assert item["createdAt"].endswith("Z")
        assert any(row["conversationId"] == identity for row in call("/agent/conversations")["items"])
        login("operator")
        assert call("/agent/conversations")["total"] == 0
        call("/agent/conversations/"+identity, expected=(404,))
        login("admin")
        call("/agent/conversations", expected=(403,))
        token = supervisor
        subprocess.run(["docker", "compose", "-f", "compose.yml", "-f", "infra/compose.agent-test.yml",
                        "--profile", "agent", "restart", "agent-service"], cwd=root, env=os.environ,
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        deadline = time.monotonic()+45
        while True:
            try:
                assert call("/agent/conversations/"+identity)["title"] == item["title"]
                break
            except (AssertionError, OSError):
                if time.monotonic() > deadline:
                    raise
                time.sleep(1)
        call("/auth/logout", {})
        call("/agent/conversations", expected=(401,))
        print("Gateway/Core/Agent checks passed: current authorization, owner isolation, UTC response, restart persistence and revoked session rejection.")
    finally:
        for saved in sessions:
            token = saved
            call("/auth/logout", {}, expected=(200,401))


if __name__ == "__main__":
    main()
