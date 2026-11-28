import httpx
import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from app.config import Settings
from app.main import create_app


def test_missing_configuration_is_rejected(monkeypatch):
    import os

    for name in list(os.environ):
        if name.startswith(("AGENT_", "DEEPSEEK_")):
            monkeypatch.delenv(name)
    with pytest.raises(ValidationError):
        Settings(_env_file=None)


@pytest.mark.parametrize(
    "url",
    [
        "http://platform-service:8087",
        "https://user:password@platform-service",
        "https://platform-service/other",
    ],
)
def test_business_origins_are_fixed_tls_roots(settings, url):
    values = settings.model_dump(by_alias=True)
    values["platform_base_url"] = url
    with pytest.raises(ValidationError):
        Settings(**values)


def test_live_is_independent_and_readiness_reports_dependency_failure(settings):
    with TestClient(create_app(settings)) as client:

        def database_down():
            raise RuntimeError("database unavailable")

        client.app.state.database.ready = database_down

        async def unavailable(_):
            return httpx.Response(503)

        client.app.state.http._transport = httpx.MockTransport(unavailable)
        client.app.state.internal_http._transport = httpx.MockTransport(unavailable)
        assert client.get("/health/live").status_code == 200
        assert client.get("/health/ready").status_code == 503
        response = client.get("/health/diagnostics")
        assert response.status_code == 503
        assert response.json()["components"] == {
            name: "DOWN" for name in ("mysql", "platform", "device", "fault", "workflow", "model")
        }
        assert "offline-fixture-only" not in response.text


def test_readiness_never_calls_model_or_downstream(settings):
    with TestClient(create_app(settings)) as client:
        client.app.state.database.ready = lambda: None
        async def forbidden(_):
            raise AssertionError("Probe must not contact a dependency")
        client.app.state.http._transport = httpx.MockTransport(forbidden)
        client.app.state.internal_http._transport = httpx.MockTransport(forbidden)
        assert client.get("/health/ready").status_code == 200
        assert client.get("/.well-known/agent-jwks.json").status_code == 200
