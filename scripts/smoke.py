"""Verify the running local stack through its public HTTP entry point."""

import argparse
import json
import subprocess
import urllib.error
import urllib.request
from pathlib import Path


def configuration() -> dict[str, str]:
    return dict(
        line.split("=", 1)
        for line in (Path(__file__).resolve().parents[1] / ".env").read_text().splitlines()
        if line and not line.startswith("#")
    )


def call(base_url: str, path: str, expected: int, body=None, token=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(
        base_url + path,
        data=None if body is None else json.dumps(body).encode(),
        headers=headers,
    )
    try:
        response = urllib.request.urlopen(request, timeout=15)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        assert response.code == expected, f"{path} returned {response.code}, expected {expected}"
        return json.load(response)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8088")
    parser.add_argument("--check-databases", action="store_true")
    args = parser.parse_args()
    values = configuration()
    call(args.base_url, "/api/v1/health", 401)
    call(args.base_url, "/api/v1/health", 401, token="invalid")
    call(args.base_url, "/api/v1/auth/login", 401, {"username": "admin", "password": "invalid"})
    call(args.base_url, "/api/v1/auth/login", 400, {"username": "admin"})
    for username, role in (("admin", "ADMIN"), ("operator", "OPERATOR")):
        result = call(args.base_url, "/api/v1/auth/login", 200, {
            "username": username, "password": values[f"DEMO_{username.upper()}_PASSWORD"]
        })
        assert result["data"]["user"] == {"username": username, "role": role, "campusId": "CAMPUS-A"}
        health = call(args.base_url, "/api/v1/health", 200, token=result["data"]["accessToken"])
        assert health["data"]["service"] == "device-service" and health["data"]["status"] == "UP"
    if args.check_databases:
        for owner in ("device", "alarm", "workflow"):
            sql = f"SELECT COUNT(*) FROM {owner}_db.flyway_schema_history WHERE success = 1"
            result = subprocess.run([
                "docker", "compose", "exec", "-T", "mysql", "sh", "-c",
                f'MYSQL_PWD="${owner.upper()}_DB_PASSWORD" mysql -u{owner}_app -N -e "{sql}"',
            ], check=True, capture_output=True, text=True)
            expected = {"device": "5", "alarm": "2", "workflow": "1"}[owner]
            assert result.stdout.strip() == expected, f"{owner} migration missing"
            other = "alarm" if owner == "device" else "device"
            denied = subprocess.run([
                "docker", "compose", "exec", "-T", "mysql", "sh", "-c",
                f'MYSQL_PWD="${owner.upper()}_DB_PASSWORD" mysql -u{owner}_app -e "USE {other}_db"',
            ], capture_output=True)
            assert denied.returncode != 0 and b"Access denied" in denied.stderr, f"{owner} cross-database access permitted"
    print("PASS: authentication, both roles, protected gateway route" +
          (", migrations and database isolation" if args.check_databases else ""))


if __name__ == "__main__":
    main()
