"""Explicit isolated MS-09 schema isolation, dependency outage and restart checks."""

import argparse
import json
import os
import subprocess
import time
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from microservices_common import APPS, Target, command_env, run, write_json
from smoke_context import api_base, credentials, tls_context


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report-dir", required=True, type=Path)
    args = parser.parse_args()
    target = Target(**json.loads(os.environ["CAPYIOT_MS_TARGET"]), verification=True)
    target.preflight()
    args.report_dir.mkdir(exist_ok=False, parents=True)
    report = {"status": "RUNNING", "project": target.project, "checks": []}
    tls = tls_context()
    token = None
    stopped_service = None

    def call(path, body=None):
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        try:
            response = urlopen(
                Request(
                    api_base() + path,
                    headers=headers,
                    data=json.dumps(body).encode() if body is not None else None,
                ),
                context=tls,
                timeout=20,
            )
        except HTTPError as error:
            response = error
        with response:
            return response.status, json.load(response)

    def ready(service):
        end = time.monotonic() + 120
        name = target.project + "-" + service + "-1"
        while time.monotonic() < end:
            state = json.loads(
                run(["docker", "inspect", name, "--format", "{{json .State}} "])
            )
            if state.get("Health", {}).get("Status") == "healthy":
                return
            time.sleep(1)
        raise AssertionError("Independent restart did not become ready: " + service)

    try:
        status, identity = call(
            "/auth/login",
            {
                "loginName": "supervisor",
                "password": credentials()["DEMO_ADMIN_PASSWORD"],
            },
        )
        assert status == 200
        token = identity["data"]["accessToken"]
        # Probe actual grants, not only credentials in configuration or source code.
        for owner in ("device", "alarm", "workflow", "platform", "agent"):
            for schema in ("device", "alarm", "workflow", "platform", "agent"):
                command = target.compose(
                    "exec",
                    "-T",
                    "mysql",
                    "sh",
                    "-c",
                    f'MYSQL_PWD="$(cat /run/secrets/{owner}-db-password)" exec mysql -u{owner}_app -N -B -e "USE {schema}_db; SELECT 1"',
                )
                result = subprocess.run(
                    command,
                    env=command_env(command),
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    check=False,
                )
                if owner == schema:
                    assert result.returncode == 0, "Own schema unavailable"
                else:
                    assert result.returncode != 0 and b"1044" in result.stderr, (
                        "Cross-schema access was not explicitly denied"
                    )
                report["checks"].append(
                    {
                        "schemaOwner": owner,
                        "targetSchema": schema,
                        "allowed": owner == schema,
                    }
                )
        for service, path in (
            ("platform-service", "/devices"),
            ("workflow-service", "/workorders"),
            ("alarm-service", "/incidents"),
        ):
            stopped_service = service
            run(
                [
                    "docker",
                    "stop",
                    "--time",
                    "30",
                    target.project + "-" + service + "-1",
                ]
            )
            started = time.monotonic()
            status, body = call(path)
            report["checks"].append(
                {
                    "dependencyStopped": service,
                    "path": path,
                    "httpStatus": status,
                    "code": body.get("code"),
                    "elapsedSeconds": round(time.monotonic() - started, 3),
                }
            )
            assert status in (502, 503, 504), (
                "Unavailable service must not appear healthy, empty, or an unclassified 500"
            )
            run(["docker", "start", target.project + "-" + service + "-1"])
            ready(service)
            stopped_service = None
            assert call(path)[0] == 200
        for service in APPS:
            run(
                [
                    "docker",
                    "restart",
                    "--time",
                    "30",
                    target.project + "-" + service + "-1",
                ]
            )
            ready(service)
            report["checks"].append({"independentRestart": service, "healthy": True})
        assert call("/devices")[0] == 200
        assert call("/auth/me")[0] == 200
        report["status"] = "PASSED"
    finally:
        if stopped_service:
            run(["docker", "start", target.project + "-" + stopped_service + "-1"])
            ready(stopped_service)
        if token:
            call("/auth/logout", {})
        if report["status"] == "RUNNING":
            report["status"] = "FAILED"
        write_json(args.report_dir / "report.json", report)
    print("Isolated schema, outage and independent restart checks passed")


if __name__ == "__main__":
    main()
