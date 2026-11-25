"""Explicit sequential stage-three verification; real platform plus deterministic offline model."""

import argparse
import hashlib
import json
import os
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--resume-report", type=Path)
    args = parser.parse_args()
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    folder = ROOT / "var/verification" / ("query-" + stamp)
    folder.mkdir(parents=True)
    project = "capyiot-query-" + stamp.lower()
    simulator = project + "-simulator"
    devices = folder / "video-devices.json"
    devices.write_text("[]", encoding="utf-8")
    env = {
        **os.environ,
        "COMPOSE_PROJECT_NAME": project,
        "VIDEO_DEVICE_FILE": devices.as_posix(),
        "DEEPSEEK_API_KEY": "offline-fixture-only",
        "DEEPSEEK_MODEL": "offline-fixture",
        "DEEPSEEK_BASE_URL": "http://model-fixture:8099",
        "VERIFY_AGENT_DEVICE_RUNTIME": "true",
        "VERIFY_AGENT_FAULT_RUNTIME": "true",
        "VERIFY_AGENT_DOMAIN_RUNTIME": "true",
        "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT": "",
    }
    if os.name == "nt":
        candidates = sorted((Path.home() / ".jdks").glob("jdk-21*"), reverse=True)
        if candidates:
            env["JAVA_HOME"] = str(candidates[0])
            env["PATH"] = str(candidates[0] / "bin") + os.pathsep + env["PATH"]
    compose = [
        "docker",
        "compose",
        "-f",
        "compose.yml",
        "-f",
        "infra/compose.agent-test.yml",
        "--profile",
        "agent",
    ]
    previous = json.loads(args.resume_report.read_text()) if args.resume_report else {}
    report = {
        "status": "RUNNING",
        "modelMode": "OFFLINE_FIXTURE",
        "startedAt": stamp,
        "composeProject": project,
        "steps": [],
    }
    launched = False

    def step(name, command, cwd=ROOT, prefixes=None):
        digest = None
        if prefixes:
            paths = subprocess.check_output(
                ["git", "ls-files", "--cached", "--others", "--exclude-standard"],
                cwd=ROOT,
                text=True,
            ).splitlines()
            hashed = hashlib.sha256()
            for path in sorted(set(paths)):
                if path.startswith(prefixes) and (ROOT / path).is_file():
                    hashed.update(path.encode())
                    hashed.update((ROOT / path).read_bytes())
            digest = hashed.hexdigest()
            old = next(
                (
                    entry
                    for entry in previous.get("steps", [])
                    if entry["name"] == name
                    and entry["exitCode"] == 0
                    and entry.get("inputHash") == digest
                ),
                None,
            )
            if old:
                report["steps"].append(
                    {**old, "reusedFrom": str(args.resume_report.resolve())}
                )
                print("Reusing unchanged check: " + name, flush=True)
                return
        print("Running " + name, flush=True)
        if os.name == "nt" and str(command[0]).endswith(".cmd"):
            command = [os.environ.get("COMSPEC", "cmd.exe"), "/d", "/c", *command]
        with (folder / (name + ".log")).open("wb") as output:
            result = subprocess.run(
                command, cwd=cwd, env=env, stdout=output, stderr=subprocess.STDOUT
            )
        report["steps"].append(
            {"name": name, "exitCode": result.returncode, "inputHash": digest}
        )
        (folder / "report.json").write_text(
            json.dumps(report, indent=2), encoding="utf-8"
        )
        if result.returncode:
            raise RuntimeError(name + " failed; inspect the private verification log")

    running = subprocess.check_output(
        ["docker", "ps", "--format", '{{.Label "com.docker.compose.project"}}'],
        text=True,
    )
    if any(line.startswith("capyiot") for line in running.splitlines()):
        raise SystemExit(
            "Stop existing CapyIoT Compose services first; keep the volumes."
        )
    try:
        step("dependencies", ["uv", "sync", "--locked"], ROOT / "agent-service")
        step(
            "lint",
            ["uv", "run", "--no-sync", "ruff", "check", "."],
            ROOT / "agent-service",
            ("agent-service/",),
        )
        step(
            "format",
            ["uv", "run", "--no-sync", "ruff", "format", "--check", "."],
            ROOT / "agent-service",
            ("agent-service/",),
        )
        step(
            "python-tests",
            ["uv", "run", "--no-sync", "pytest", "-q"],
            ROOT / "agent-service",
            ("agent-service/",),
        )
        maven = str(ROOT / "backend" / ("mvnw.cmd" if os.name == "nt" else "mvnw"))
        step(
            "core-tests",
            [maven, "-B", "-ntp", "-pl", "core-service", "-am", "verify"],
            ROOT / "backend",
            ("backend/",),
        )
        step("local-credentials", [sys.executable, "scripts/init_env.py"])
        step("local-tls", [sys.executable, "scripts/init_tls.py"])
        step("compose-config", compose + ["config", "--quiet"])
        launched = True
        step(
            "compose-start",
            compose + ["up", "-d", "--build", "--wait", "--wait-timeout", "300"],
        )
        step(
            "video-provision",
            [sys.executable, "scripts/video_devices.py", "--output", str(devices)],
        )
        step(
            "simulator-image",
            compose + ["--profile", "simulation", "build", "simulator"],
        )
        step(
            "simulator-start",
            compose
            + [
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
                "90",
            ],
        )
        step("agent-query", [sys.executable, "scripts/agent_query_smoke.py"])
        report["status"] = "PASSED"
    except Exception as error:
        report["status"], report["error"] = "FAILED", str(error)
        raise
    finally:
        if launched:
            with (folder / "simulator.log").open("wb") as output:
                subprocess.run(
                    ["docker", "logs", "--tail", "100", simulator],
                    stdout=output,
                    stderr=subprocess.STDOUT,
                )
            subprocess.run(
                ["docker", "stop", simulator],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            with (folder / "compose.log").open("wb") as output:
                subprocess.run(
                    compose + ["logs", "--tail", "200"],
                    cwd=ROOT,
                    env=env,
                    stdout=output,
                    stderr=subprocess.STDOUT,
                )
            subprocess.run(
                compose + ["down"],
                cwd=ROOT,
                env=env,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
        report["finishedAt"] = datetime.now(timezone.utc).isoformat()
        (folder / "report.json").write_text(
            json.dumps(report, indent=2), encoding="utf-8"
        )
        print("Local verification report: " + str(folder / "report.json"))


if __name__ == "__main__":
    main()
