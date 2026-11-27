"""Seven-app routing, current identity and public/internal boundaries in an isolated stack."""

import json
import ssl
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from microservices_common import APPS, Target, run
from smoke_context import api_base, credentials


def main():
    import os

    tls = ssl.create_default_context(cafile=os.environ["CAPYIOT_CA_FILE"])
    token = None

    def call(path, body=None, status=200):
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        try:
            response = urlopen(
                Request(
                    api_base() + path,
                    headers=headers,
                    data=None if body is None else json.dumps(body).encode(),
                ),
                context=tls,
                timeout=30,
            )
        except HTTPError as error:
            response = error
        with response:
            assert response.status == status, (path, response.status)
            return json.load(response)

    for attempt in range(30):
        try:
            token = call(
                "/auth/login",
                {
                    "loginName": "admin",
                    "password": credentials()["DEMO_ADMIN_PASSWORD"],
                },
            )["data"]["accessToken"]
            break
        except AssertionError as error:
            if error.args != (("/auth/login", 503),) or attempt == 29:
                raise
            print(
                "Startup authorization/JWKS warm-up returned 503; bounded readiness wait",
                flush=True,
            )
            time.sleep(1)
    try:
        assert call("/auth/me")["data"]["userId"]
        health = call("/observability/services")["data"]["services"]
        assert {item["service"] for item in health} == set(APPS)
        assert all(item["status"] == "UP" for item in health)
        catalog = call("/openapi")["data"]["services"]
        assert {item["service"] for item in catalog} == set(APPS) - {
            "gateway",
            "device-ingestion",
        }
        for url in (
            "/openapi/device",
            "/openapi/fault",
            "/openapi/workflow",
            "/openapi/platform",
            "/agent/openapi",
        ):
            assert call(url).get("openapi")
        for retired in ("/stream", "/alarms/stream", "/workorders/stream"):
            call(retired, status=410)
        target = Target(
            **json.loads(os.environ["CAPYIOT_MS_TARGET"]), verification=True
        )
        result = run(
            target.compose(
                "exec",
                "-T",
                "gateway",
                "curl",
                "--silent",
                "--cacert",
                "/run/secrets/internal-ca.pem",
                "--output",
                "/dev/null",
                "--write-out",
                "%{http_code}",
                "https://gateway:8080/internal/v1/identity/context",
                active=True,
                offline=True,
            )
        )
        assert result == b"403"
        call("/observability/metrics?service=core-service", status=400)
    finally:
        call("/auth/logout", {})
    call("/auth/me", status=401)
    print("Isolated seven-app identity, route and health checks completed")


if __name__ == "__main__":
    main()
