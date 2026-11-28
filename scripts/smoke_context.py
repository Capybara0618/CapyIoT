"""Inject an explicit isolated target into retained acceptance scenarios."""

import json
import os
import ssl
import subprocess
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


def active_outage_incident(call):
    """Select this scenario's active outage, excluding restart-era recovered candidates."""
    anomalies = call("/anomalies?status=ACTIVE&size=100")["items"]
    active = {row["anomalyId"] for row in anomalies}
    roots = {
        row["anomalyId"]
        for row in anomalies
        if row["anomalyType"] == "SWITCH_DEVICE_ERROR"
    }
    for incident in call("/incidents?status=PENDING_REVIEW&size=100")["items"]:
        detail = call("/incidents/" + incident["incidentId"])
        linked = {row["anomalyId"] for row in detail["anomalyLinks"]}
        if linked & roots and len(linked & active) >= 3:
            return incident
    return None


def compose_command(*args):
    if os.environ.get("CAPYIOT_CN_TARGET"):
        from cloud_native_common import Target as CloudTarget
        target = CloudTarget(**json.loads(os.environ["CAPYIOT_CN_TARGET"]))
        target.assert_daemon(target.state())
        target.assert_cluster(target.state())
        if args != ("restart", "agent-service"):
            raise ValueError("Retained CN scenario only permits the explicit Agent restart")
        return target.kubectl("rollout", "restart", "deployment/agent-service")
    selected = os.environ.get("CAPYIOT_MS_TARGET")
    if selected:
        target = Target(**json.loads(selected), verification=True)
        target.preflight()
        return target.compose(*args, active=True, offline=True)
    raise ValueError(
        "Explicit isolated CAPYIOT_MS_TARGET is required; no default deployment is selected"
    )


def wait_agent_restart():
    if os.environ.get("CAPYIOT_CN_TARGET"):
        from cloud_native_common import Target as CloudTarget
        target = CloudTarget(**json.loads(os.environ["CAPYIOT_CN_TARGET"]))
        subprocess.run(target.kubectl("rollout", "status", "deployment/agent-service", "--timeout=180s"),
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
