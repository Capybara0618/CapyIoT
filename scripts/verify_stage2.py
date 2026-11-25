"""Explicit offline stage-two verification after coding; never calls a real DeepSeek model."""

import json
import os
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main():
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    folder = ROOT / "var" / "verification" / ("agent-" + stamp)
    folder.mkdir(parents=True)
    env = {
        **os.environ,
        "COMPOSE_PROJECT_NAME": "capyiot-agent-" + stamp.lower(),
        "DEEPSEEK_API_KEY": "offline-fixture-only",
        "DEEPSEEK_MODEL": "offline-fixture",
        "DEEPSEEK_BASE_URL": "http://model-fixture:8099",
        "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT": "",
    }
    report = {
        "status": "RUNNING",
        "modelMode": "OFFLINE_FIXTURE",
        "steps": [],
        "startedAt": stamp,
    }
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
    launched = False

    def step(name, command, cwd=ROOT):
        print("Running " + name, flush=True)
        with (folder / (name + ".log")).open("wb") as output:
            result = subprocess.run(
                command, cwd=cwd, env=env, stdout=output, stderr=subprocess.STDOUT
            )
        report["steps"].append({"name": name, "exitCode": result.returncode})
        if result.returncode:
            raise RuntimeError(name + " failed; inspect the local verification log")

    running = subprocess.check_output(
        ["docker", "ps", "--format", '{{.Label "com.docker.compose.project"}}'],
        text=True,
    )
    if any(line.startswith("capyiot") for line in running.splitlines()):
        raise SystemExit(
            "Stop the existing CapyIoT Compose environment first; keep its volumes."
        )
    try:
        step("dependencies", ["uv", "sync", "--locked"], ROOT / "agent-service")
        step(
            "lint",
            ["uv", "run", "--no-sync", "ruff", "check", "."],
            ROOT / "agent-service",
        )
        step(
            "format",
            ["uv", "run", "--no-sync", "ruff", "format", "--check", "."],
            ROOT / "agent-service",
        )
        step(
            "tests", ["uv", "run", "--no-sync", "pytest", "-q"], ROOT / "agent-service"
        )
        step("local-credentials", [sys.executable, "scripts/init_env.py"])
        step("compose-config", compose + ["config", "--quiet"])
        launched = True
        step(
            "dependencies-start",
            compose + ["up", "-d", "--wait", "--no-deps", "mysql", "model-fixture"],
        )
        step(
            "migration",
            compose
            + [
                "up",
                "--build",
                "--no-deps",
                "--abort-on-container-exit",
                "--exit-code-from",
                "agent-migrate",
                "agent-migrate",
            ],
        )
        step(
            "services-start",
            compose
            + [
                "up",
                "-d",
                "--build",
                "--wait",
                "--wait-timeout",
                "240",
                "--no-deps",
                "core-service",
                "gateway",
                "agent-service",
            ],
        )
        step("http-integration", [sys.executable, "scripts/agent_foundation_smoke.py"])
        # Required config must fail startup, even though no business message endpoint is enabled yet.
        with (folder / "missing-config.log").open("wb") as output:
            result = subprocess.run(
                compose
                + [
                    "run",
                    "--rm",
                    "--no-deps",
                    "-e",
                    "DEEPSEEK_API_KEY=",
                    "agent-service",
                ],
                cwd=ROOT,
                env=env,
                stdout=output,
                stderr=subprocess.STDOUT,
                timeout=60,
            )
        if result.returncode == 0:
            raise RuntimeError("Missing model configuration did not fail startup")
        report["steps"].append({"name": "missing-config-rejection", "exitCode": 0})
        report["status"] = "PASSED"
    except Exception as error:
        report["status"], report["error"] = "FAILED", str(error)
        raise
    finally:
        if launched:
            with (folder / "compose.log").open("wb") as output:
                subprocess.run(
                    compose + ["logs", "--tail", "100"],
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
        print("Local report: " + str(folder / "report.json"))


if __name__ == "__main__":
    main()
