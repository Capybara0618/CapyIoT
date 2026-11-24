"""Create the Kubernetes demo Secret from the locally generated configuration."""

import argparse
import base64
import json
from pathlib import Path
import subprocess

REQUIRED = (
    "PLATFORM_DB_PASSWORD", "CORE_SERVICE_KEY",
    "MYSQL_ROOT_PASSWORD", "DEVICE_DB_PASSWORD", "ALARM_DB_PASSWORD", "WORKFLOW_DB_PASSWORD",
    "JWT_SECRET", "DEVICE_JWT_SECRET", "TOPOLOGY_SYNC_TOKEN", "DEMO_ADMIN_PASSWORD_HASH",
    "DEMO_OPERATOR_PASSWORD_HASH", "EMQX_NODE_COOKIE", "GRAFANA_ADMIN_PASSWORD",
)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--context", required=True)
    parser.add_argument("--namespace", default="capyiot")
    args = parser.parse_args()
    env = Path(__file__).resolve().parents[1] / ".env"
    values = dict(line.split("=", 1) for line in env.read_text(encoding="utf-8").splitlines()
                  if "=" in line and not line.startswith("#"))
    if any(not values.get(key) for key in REQUIRED):
        parser.error("Run python scripts/init_env.py before creating the Secret")
    manifest = {
        "apiVersion": "v1", "kind": "Secret",
        "metadata": {"name": "capyiot-secrets", "namespace": args.namespace},
        "type": "Opaque",
        "data": {key: base64.b64encode(values[key].encode()).decode() for key in REQUIRED},
    }
    subprocess.run(["kubectl", "--context", args.context, "apply", "--server-side", "-f", "-"],
                   input=json.dumps(manifest), text=True, check=True)


if __name__ == "__main__":
    main()
