"""Explicit deployment targets shared by the MS rollout and verification commands."""

import hashlib
import json
import os
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APPS = (
    "platform-service",
    "device-service",
    "alarm-service",
    "workflow-service",
    "gateway",
    "device-ingestion",
    "agent-service",
)
JAVA_APPS = APPS[:-1]
SCHEMAS = ("device_db", "alarm_db", "workflow_db", "platform_db", "agent_db")
OLD_APPS = (*APPS, "core-service", "frontend", "simulator", "agent-migrate")
INFRA = {
    "mysql",
    "redis",
    "kafka",
    "emqx",
    "prometheus",
    "grafana",
    "jaeger",
    "model-fixture",
}
GROUPS = (
    "core-device-state-v1",
    "core-fault-observation-v1",
    "core-fault-workorder-v1",
    "core-platform-audit-v1",
)


def name(value):
    if not re.fullmatch(r"[a-z0-9][a-z0-9_-]{2,62}", value):
        raise ValueError("Invalid explicit Compose project or volume prefix")
    return value


def private_path(path):
    path = Path(path).resolve()
    ignored = (ROOT / "var").resolve()
    if path == ignored or ignored not in path.parents:
        raise ValueError(
            "Local bundles and reports must be below ignored workspace var/"
        )
    return path


def read_env(path):
    result = {}
    for line in Path(path).read_text(encoding="utf-8-sig").splitlines():
        if line and not line.startswith("#"):
            key, sep, value = line.partition("=")
            if not sep or key in result or not re.fullmatch(r"[A-Z][A-Z0-9_]*", key):
                raise ValueError("Invalid or duplicate environment key")
            result[key] = value
    return result


def write_json(path, value):
    path = Path(path)
    tmp = path.with_suffix(path.suffix + ".tmp")
    with tmp.open("w", encoding="utf-8") as output:
        json.dump(value, output, ensure_ascii=False, indent=2)
        output.flush()
        os.fsync(output.fileno())
    os.replace(tmp, path)
    path.chmod(0o600)


def sha(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def command_env(argv, inherited=None):
    env = dict(os.environ if inherited is None else inherited)
    if (
        len(argv) > 1
        and str(argv[0]) == "docker"
        and str(argv[1]) == "compose"
        and "--env-file" in argv
    ):
        for key in list(env):
            if key.startswith(("MS_", "COMPOSE_", "DEEPSEEK_", "OTEL_EXPORTER_")):
                env.pop(key)
        for i, arg in enumerate(argv[:-1]):
            if arg == "--env-file":
                env.update(read_env(argv[i + 1]))
    return env


def run(argv, *, data=None, output=None):
    """Never print Docker inspect/env/SQL diagnostics which may contain credentials."""
    result = subprocess.run(
        list(map(str, argv)),
        cwd=ROOT,
        input=data,
        env=command_env(argv),
        stdout=output if output else subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if result.returncode:
        raise RuntimeError(
            "Deployment command failed; target state must be inspected before continuing"
        )
    return result.stdout if output is None else b""


def containers(project):
    ids = (
        run(
            [
                "docker",
                "ps",
                "-a",
                "--filter",
                "label=com.docker.compose.project=" + name(project),
                "-q",
            ]
        )
        .decode()
        .split()
    )
    if not ids:
        return []
    # Do not return Config.Env or credentials from inspect to logs/reports.
    objects = json.loads(run(["docker", "inspect", *ids]))
    return [
        {
            "id": obj["Id"],
            "service": obj["Config"]["Labels"].get("com.docker.compose.service"),
            "image": obj["Image"],
            "running": obj["State"]["Running"],
            "mounts": [
                {k: mount.get(k) for k in ("Type", "Name", "Source", "Destination")}
                for mount in obj["Mounts"]
            ],
        }
        for obj in objects
    ]


class Target:
    def __init__(self, project, compose_file, env_file, *, verification=False):
        self.project = name(project)
        self.compose_file = Path(compose_file).resolve()
        self.env_file = private_path(env_file)
        if verification and not self.project.startswith("capyiot-ms-verify-"):
            raise ValueError("Verification requires a capyiot-ms-verify-* project")
        if self.compose_file != ROOT / "compose.microservices.yml":
            raise ValueError(
                "Select the standalone candidate Compose file, never the old Core stack"
            )
        self.values = read_env(self.env_file)
        self.bundle = private_path(self.values["MS_BUNDLE"])
        self.manifest = json.loads(
            (self.bundle / "manifest.json").read_text(encoding="utf-8")
        )
        if (
            self.values["MS_PROJECT"] != self.project
            or self.manifest["project"] != self.project
        ):
            raise ValueError("Bundle, env and Compose project must match")
        if verification and (
            self.manifest["mode"] != "fresh"
            or not self.values["MS_VOLUME_PREFIX"].startswith(self.project)
        ):
            raise ValueError("Verification must use fresh, project-owned volumes")

    def compose(self, *args, active=False, offline=False):
        command = [
            "docker",
            "compose",
            "--project-directory",
            str(ROOT),
            "-p",
            self.project,
            "--env-file",
            str(self.env_file),
        ]
        if active:
            if read_env(self.bundle / "active.env") != {
                "MS_WRITERS_ENABLED": "true",
                "MS_PLATFORM_BOOTSTRAP": "false",
            }:
                raise ValueError(
                    "The activation override may only enable the native writer gate and disable bootstrap"
                )
            command += ["--env-file", str(self.bundle / "active.env")]
        command += ["-f", str(self.compose_file)]
        if offline:
            command += ["-f", str(ROOT / "infra/compose.microservices-test.yml")]
        return command + list(args)

    def preflight(self):
        if self.manifest.get("status") != "READY":
            raise ValueError("Initialization did not finish; do not start this bundle")
        for relative, expected in self.manifest["files"].items():
            path = (self.bundle / relative).resolve()
            if (
                self.bundle not in path.parents
                or not path.is_file()
                or sha(path) != expected
            ):
                raise ValueError(
                    "Bundle file changed or missing; review/reinitialize without rotating old material"
                )
        if self.values.get("MS_WRITERS_ENABLED") != "false":
            raise ValueError(
                "The base env must remain passive; activation uses a separate explicit override"
            )
        if (
            self.values["MS_VOLUME_PREFIX"] != self.manifest["volumePrefix"]
            or self.values["MS_MODEL_MODE"] != self.manifest["modelMode"]
        ):
            raise ValueError(
                "Bundle volume identity and model mode are immutable; provision a separate bundle"
            )
        return self.manifest

    def sql(self, statement):
        return run(
            self.compose(
                "exec",
                "-T",
                "mysql",
                "sh",
                "-c",
                'MYSQL_PWD="$(cat /run/secrets/mysql-root-password)" exec mysql -uroot -N -B',
            ),
            data=statement.encode(),
        ).decode()
