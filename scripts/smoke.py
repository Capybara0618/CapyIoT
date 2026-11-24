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
    for username, role, password_key in (("admin", "SYS_ADMIN", "DEMO_ADMIN_PASSWORD"),
                                         ("supervisor", "OPS_SUPERVISOR", "DEMO_ADMIN_PASSWORD"),
                                         ("operator", "OPS_OPERATOR", "DEMO_OPERATOR_PASSWORD")):
        result = call(args.base_url, "/api/v1/auth/login", 200, {
            "loginName": username, "password": values[password_key]
        })["data"]
        assert result["user"]["userId"] and result["user"]["authorizationVersion"] >= 1
        assert role in result["user"]["roleCodes"]
        token = result["accessToken"]
        current = call(args.base_url, "/api/v1/auth/me", 200, token=token)["data"]
        assert role in current["roleCodes"]
        if username == "admin":
            assert "device.read" not in current["permissionCodes"]
            call(args.base_url, "/api/v1/users", 200, token=token)
        else:
            call(args.base_url, "/api/v1/users", 403, token=token)
            health = call(args.base_url, "/api/v1/health", 200, token=token)
            assert health["data"]["service"] == "device-service" and health["data"]["status"] == "UP"
        call(args.base_url, "/api/v1/auth/logout", 200, {}, token=token)
        call(args.base_url, "/api/v1/auth/me", 401, token=token)
    if args.check_databases:
        for owner in ("device", "alarm", "workflow", "platform"):
            sql = f"SELECT COUNT(*) FROM {owner}_db.flyway_schema_history WHERE success = 1"
            result = subprocess.run([
                "docker", "compose", "exec", "-T", "mysql", "sh", "-c",
                f'MYSQL_PWD="${owner.upper()}_DB_PASSWORD" mysql -u{owner}_app -N -e "{sql}"',
            ], check=True, capture_output=True, text=True)
            expected = {"device": 7, "alarm": 5, "workflow": 3, "platform": 2}[owner]
            assert int(result.stdout.strip()) >= expected, f"{owner} migration missing"
            other = "alarm" if owner == "device" else "device"
            denied = subprocess.run([
                "docker", "compose", "exec", "-T", "mysql", "sh", "-c",
                f'MYSQL_PWD="${owner.upper()}_DB_PASSWORD" mysql -u{owner}_app -e "USE {other}_db"',
            ], capture_output=True)
            assert denied.returncode != 0 and b"Access denied" in denied.stderr, f"{owner} cross-database access permitted"
    print("PASS: authentication, live authorization, role separation, logout and protected gateway route" +
          (", migrations and database isolation" if args.check_databases else ""))


if __name__ == "__main__":
    main()
