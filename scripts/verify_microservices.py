"""Run selected MS-09 checks against an explicitly selected, fresh isolated project.

Never calls verify_stage1-5 (their old default target is unsuitable). Reuses their
applicable protocol/workflow/Agent scenarios after injecting this exact target.
"""

import argparse
import json
import os
import shutil
import socket
import subprocess
import sys
import uuid
from datetime import datetime, timezone
from pathlib import Path

from microservices_common import (
    APPS,
    JAVA_APPS,
    ROOT,
    Target,
    command_env,
    containers,
    private_path,
    run,
    write_json,
)

GROUPS = (
    "unit",
    "build",
    "runtime",
    "protocol",
    "workflow",
    "agent",
    "browser",
    "recovery",
)


def verify(args):
    target = Target(args.project, args.compose_file, args.env_file, verification=True)
    target.preflight()
    if target.manifest["modelMode"] != "offline":
        raise ValueError(
            "Unified offline verification cannot use real model credentials"
        )
    if "recovery" in args.groups and len(target.project) > 55:
        raise ValueError("Leave room in the project name for a separate restore target")
    folder = private_path(args.report_dir)
    if folder.exists():
        raise ValueError(
            "Select a new report directory; failed runs and evidence are never overwritten"
        )
    if target.bundle in folder.parents:
        raise ValueError("Keep the report directory outside the credential bundle")
    folder.mkdir(parents=True, mode=0o700)
    report = {
        "status": "RUNNING",
        "project": target.project,
        "groups": args.groups,
        "modelMode": "OFFLINE_FIXTURE",
        "commit": run(["git", "rev-parse", "HEAD"]).decode().strip(),
        "startedAt": datetime.now(timezone.utc).isoformat(),
        "steps": [],
    }
    env = os.environ.copy()
    env.pop("COMPOSE_PROJECT_NAME", None)
    for key in ("COMPOSE_FILE", "COMPOSE_PROFILES", "COMPOSE_ENV_FILES"):
        env.pop(key, None)
    # Never read the developer's .env, reset credentials, or run a real provider implicitly.
    credential_file = folder / "scenario.env"
    credential_file.write_text(
        "\n".join(
            key
            + "="
            + (target.bundle / "secrets" / key.lower().replace("_", "-")).read_text(
                encoding="utf-8"
            )
            for key in ("DEMO_ADMIN_PASSWORD", "DEMO_OPERATOR_PASSWORD")
        )
        + "\n",
        encoding="utf-8",
    )
    credential_file.chmod(0o600)
    https = "https://localhost:" + target.values["MS_HTTPS_PORT"]
    ca = target.bundle / "pki/edge/ca.pem"
    devices = target.bundle / "video-devices.json"
    env.update(
        CAPYIOT_CREDENTIAL_FILE=str(credential_file),
        CAPYIOT_CA_FILE=str(ca),
        CAPYIOT_API_BASE=https + "/api/v1",
        CAPYIOT_BASE_URL="http://127.0.0.1:" + target.values["MS_HTTP_PORT"],
        CAPYIOT_AGENT_LIVE_TESTS="true",
        CAPYIOT_MS_TARGET=json.dumps(
            {
                "project": target.project,
                "compose_file": str(target.compose_file),
                "env_file": str(target.env_file),
            }
        ),
    )
    if os.name == "nt":
        jdks = sorted((Path.home() / ".jdks").glob("jdk-21*"), reverse=True)
        if jdks:
            env["JAVA_HOME"] = str(jdks[0])
            env["PATH"] = str(jdks[0] / "bin") + os.pathsep + env.get("PATH", "")

    def step(label, command, cwd=ROOT):
        started = datetime.now(timezone.utc).isoformat()
        command = list(map(str, command))
        if os.name == "nt" and command[0].endswith(".cmd"):
            command = [os.environ.get("COMSPEC", "cmd.exe"), "/d", "/c", *command]
        print("Running selected check: " + label, flush=True)
        with (folder / (label + ".log")).open("xb") as output:
            result = subprocess.run(
                command,
                cwd=cwd,
                env=command_env(command, env),
                stdout=output,
                stderr=subprocess.STDOUT,
            )
        report["steps"].append(
            {
                "name": label,
                "exitCode": result.returncode,
                "startedAt": started,
                "finishedAt": datetime.now(timezone.utc).isoformat(),
            }
        )
        write_json(folder / "report.json", report)
        if result.returncode:
            raise RuntimeError("Selected check failed: " + label)

    def control(action):
        step(
            "rollout-" + action,
            [
                sys.executable,
                ROOT / "scripts/microservices_control.py",
                action,
                "--project",
                target.project,
                "--compose-file",
                target.compose_file,
                "--env-file",
                target.env_file,
                "--report-dir",
                folder / "rollout",
                "--execute",
            ],
        )

    def scenario(script, *extra):
        step(
            script,
            [
                sys.executable,
                ROOT / ("scripts/" + script + ".py"),
                "--base-url",
                https,
                "--ca-file",
                ca,
                "--env-file",
                credential_file,
                *extra,
            ],
        )

    npm = shutil.which("npm.cmd" if os.name == "nt" else "npm")
    maven = ROOT / "backend" / ("mvnw.cmd" if os.name == "nt" else "mvnw")
    launched = False
    restore_target = None
    simulator = None
    try:
        if "unit" in args.groups:
            step(
                "source-boundaries",
                [
                    sys.executable,
                    "scripts/check_microservices_boundaries.py",
                    "--report",
                    folder / "boundaries.json",
                ],
            )
            step(
                "backend",
                [
                    maven,
                    "-B",
                    "-ntp",
                    "--fail-at-end",
                    "-pl",
                    ",".join(JAVA_APPS),
                    "-am",
                    "clean",
                    "verify",
                ],
                ROOT / "backend",
            )
            step(
                "simulator-tests",
                [maven, "-B", "-ntp", "-f", ROOT / "simulator/pom.xml", "verify"],
                ROOT / "backend",
            )
            step("agent-install", ["uv", "sync", "--locked"], ROOT / "agent-service")
            step(
                "agent-lint",
                ["uv", "run", "--no-sync", "ruff", "check", "."],
                ROOT / "agent-service",
            )
            step(
                "agent-format",
                ["uv", "run", "--no-sync", "ruff", "format", "--check", "."],
                ROOT / "agent-service",
            )
            step(
                "agent-tests",
                ["uv", "run", "--no-sync", "pytest", "-q"],
                ROOT / "agent-service",
            )
            step(
                "deployment-tests",
                [sys.executable, "-m", "unittest", "discover", "-s", "scripts/tests"],
            )
            if not npm:
                raise ValueError("Node.js/npm is required")
            step("frontend-install", [npm, "ci"], ROOT / "frontend")
            for check in ("test:agent", "lint", "typecheck", "build"):
                step(
                    "frontend-" + check.replace(":", "-"),
                    [npm, "run", check],
                    ROOT / "frontend",
                )
        if "build" in args.groups:
            step(
                "images",
                target.compose(
                    "--profile",
                    "public",
                    "--profile",
                    "ingress",
                    "--profile",
                    "simulation",
                    "build",
                    *APPS,
                    "agent-migrate",
                    "frontend",
                    "simulator",
                    offline=True,
                ),
            )
        runtime = bool(set(args.groups) - {"unit", "build"})
        if runtime:
            if args.resume_rollout:
                previous = json.loads(
                    private_path(args.resume_rollout).read_text(encoding="utf-8")
                )
                if (
                    previous.get("project") != target.project
                    or previous.get("bundle") != str(target.bundle)
                    or previous.get("phase") != "ACTIVE"
                    or previous.get("everActivated") is not True
                    or not containers(target.project)
                ):
                    raise ValueError(
                        "Resume requires this isolated target's activated rollout record"
                    )
                # Keep the first report immutable; this is explicitly a later regression
                # against retained data, not a claim that fresh startup passed again.
                report["resumedFrom"] = str(args.resume_rollout.resolve())
                (folder / "rollout").mkdir()
                write_json(folder / "rollout/rollout.json", previous)
                launched = True
                step(
                    "resume-active",
                    target.compose(
                        "--profile",
                        "public",
                        "--profile",
                        "ingress",
                        "up",
                        "-d",
                        "--wait",
                        "--wait-timeout",
                        "360",
                        active=True,
                        offline=True,
                    ),
                )
            elif containers(target.project):
                raise ValueError(
                    "An existing target is not a fresh verification environment"
                )
            else:
                for key in ("MS_HTTP_PORT", "MS_HTTPS_PORT", "MS_MQTT_PORT"):
                    with socket.socket() as probe:
                        probe.bind(("127.0.0.1", int(target.values[key])))
                control("preflight")
                launched = True
                control("start-passive")
                control("activate")
            step(
                "seven-app-boundary", [sys.executable, "scripts/microservices_smoke.py"]
            )
        if set(args.groups) & {"protocol", "workflow", "agent", "browser"}:
            scenario("video_devices", "--output", devices)
        if "protocol" in args.groups:
            scenario(
                "video_protocol_smoke",
                "--devices",
                devices,
                "--port",
                target.values["MS_MQTT_PORT"],
            )
        if "workflow" in args.groups:
            simulator = target.project + "-outage-" + uuid.uuid4().hex[:8]
            step(
                "outage",
                target.compose(
                    "--profile",
                    "public",
                    "--profile",
                    "ingress",
                    "--profile",
                    "simulation",
                    "run",
                    "-d",
                    "--name",
                    simulator,
                    "simulator",
                    "--scenario",
                    "switch-outage",
                    "--interval-ms",
                    "1000",
                    "--fault-seconds",
                    "60",
                    active=True,
                    offline=True,
                ),
            )
            scenario("video_workflow_smoke")
        if "agent" in args.groups:
            # The manual workflow closes its Incident. Agent scenarios need their own
            # later outage, and must also work when selected without the workflow group.
            if simulator:
                step("manual-simulator-stop", ["docker", "stop", simulator])
            simulator = target.project + "-agent-outage-" + uuid.uuid4().hex[:8]
            step(
                "agent-outage",
                target.compose(
                    "--profile",
                    "public",
                    "--profile",
                    "ingress",
                    "--profile",
                    "simulation",
                    "run",
                    "-d",
                    "--name",
                    simulator,
                    "simulator",
                    "--scenario",
                    "switch-outage",
                    "--interval-ms",
                    "1000",
                    "--fault-seconds",
                    "300",
                    active=True,
                    offline=True,
                ),
            )
            for script in (
                "agent_foundation_smoke",
                "agent_query_smoke",
                "agent_draft_smoke",
            ):
                step(script, [sys.executable, ROOT / ("scripts/" + script + ".py")])
        if "browser" in args.groups:
            if not npm:
                raise ValueError("Node.js/npm is required")
            if args.install_browser:
                step(
                    "browser-install",
                    [
                        npm,
                        "exec",
                        "--",
                        "playwright",
                        "install",
                        "--with-deps",
                        "chromium",
                    ],
                    ROOT / "frontend",
                )
            step("browser", [npm, "run", "test:e2e"], ROOT / "frontend")
        if "recovery" in args.groups:
            control("freeze-active")
            control("snapshot-active")
            baseline = json.loads(
                (folder / "rollout/rollout.json").read_text(encoding="utf-8")
            )["postCutoverBackup"]
            restore_project = target.project + "-restore"
            bundle = private_path(ROOT / "var/bundles" / restore_project)
            step(
                "restore-bundle",
                [
                    "uv",
                    "run",
                    "--project",
                    "agent-service",
                    "--no-sync",
                    "python",
                    "scripts/init_microservices.py",
                    "--project",
                    restore_project,
                    "--bundle",
                    bundle,
                    "--mode",
                    "fresh",
                ],
            )
            restore_target = Target(
                restore_project,
                target.compose_file,
                bundle / "deployment.env",
                verification=True,
            )
            step(
                "restore-rehearsal",
                [
                    sys.executable,
                    "scripts/microservices_control.py",
                    "restore-rehearsal",
                    "--project",
                    restore_project,
                    "--compose-file",
                    target.compose_file,
                    "--env-file",
                    bundle / "deployment.env",
                    "--report-dir",
                    folder / "restore",
                    "--backup-dir",
                    baseline,
                    "--execute",
                ],
            )
        report["status"] = "PASSED_SELECTED_GROUPS"
    except Exception:
        report["status"] = "FAILED"
        raise
    finally:
        if simulator:
            subprocess.run(
                ["docker", "stop", simulator],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
        if restore_target:
            command = restore_target.compose("stop")
            subprocess.run(
                command,
                cwd=ROOT,
                env=command_env(command),
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
        if launched:
            with (folder / "candidate.log").open("wb") as output:
                command = target.compose(
                    "--profile",
                    "public",
                    "--profile",
                    "ingress",
                    "logs",
                    "--tail",
                    "200",
                    active=(target.bundle / "active.env").exists(),
                    offline=True,
                )
                subprocess.run(
                    command,
                    cwd=ROOT,
                    env=command_env(command),
                    stdout=output,
                    stderr=subprocess.STDOUT,
                )
            # Only this named project. Keep stopped containers, volumes and all failure evidence.
            command = target.compose(
                "--profile",
                "public",
                "--profile",
                "ingress",
                "--profile",
                "simulation",
                "stop",
                offline=True,
            )
            subprocess.run(
                command,
                cwd=ROOT,
                env=command_env(command),
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
        report["finishedAt"] = datetime.now(timezone.utc).isoformat()
        write_json(folder / "report.json", report)
        print("Private verification report: " + str(folder / "report.json"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True)
    parser.add_argument("--compose-file", type=Path, required=True)
    parser.add_argument("--env-file", type=Path, required=True)
    parser.add_argument("--report-dir", type=Path, required=True)
    parser.add_argument("--groups", nargs="+", choices=GROUPS, required=True)
    parser.add_argument("--install-browser", action="store_true")
    parser.add_argument(
        "--resume-rollout",
        type=Path,
        help="Explicit ACTIVE rollout record for a retained isolated regression target",
    )
    args = parser.parse_args()
    try:
        verify(args)
    except (ValueError, RuntimeError, OSError, KeyError):
        raise SystemExit(
            "Unified verification stopped or failed. Inspect the private report; no other project was stopped or reset."
        ) from None


if __name__ == "__main__":
    main()
