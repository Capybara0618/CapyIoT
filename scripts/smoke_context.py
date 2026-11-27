"""Inject an explicit isolated target into retained acceptance scenarios."""

import json
import os
import ssl
from pathlib import Path

from microservices_common import ROOT, Target


def credentials():
    selected = os.environ.get("CAPYIOT_CREDENTIAL_FILE")
    source = Path(selected) if selected else ROOT / ".env"
    return dict(
        line.split("=", 1)
        for line in source.read_text(encoding="utf-8-sig").splitlines()
        if "=" in line and not line.startswith("#")
    )


def api_base():
    return os.environ.get("CAPYIOT_API_BASE", "http://127.0.0.1:8080/api/v1").rstrip(
        "/"
    )


def tls_context():
    return (
        ssl.create_default_context(cafile=os.environ["CAPYIOT_CA_FILE"])
        if os.environ.get("CAPYIOT_CA_FILE")
        else None
    )


def compose_command(*args):
    selected = os.environ.get("CAPYIOT_MS_TARGET")
    if selected:
        target = Target(**json.loads(selected), verification=True)
        target.preflight()
        return target.compose(*args, active=True, offline=True)
    return [
        "docker",
        "compose",
        "-f",
        "compose.yml",
        "-f",
        "infra/compose.agent-test.yml",
        "--profile",
        "agent",
        *args,
    ]
