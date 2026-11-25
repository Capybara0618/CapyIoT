"""One explicit, sequential stage-one verification run after all coding is complete.

Uses separate Compose volumes and a separate device credential file. Does not run on push.
Raw logs and reports stay under ignored var/verification/. No measurements are fabricated.
"""

import json
import argparse
import os
import shutil
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--install-browser",action="store_true")
    args=parser.parse_args()
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    folder = ROOT / "var" / "verification" / stamp
    folder.mkdir(parents=True)
    env = os.environ.copy()
    project = "capyiot-stage1-" + stamp.lower()
    env["COMPOSE_PROJECT_NAME"] = project
    devices = folder / "video-devices.json"
    env["VIDEO_DEVICE_FILE"] = devices.as_posix()
    report = {"startedAt": stamp, "composeProject": project, "steps": [], "status": "RUNNING"}
    simulator = project + "-simulator"
    launched = False

    def execute(name, command, cwd=ROOT):
        start = datetime.now(timezone.utc)
        if os.name == "nt" and str(command[0]).endswith(".cmd"):
            command = [os.environ.get("COMSPEC", "cmd.exe"), "/d", "/c", *map(str, command)]
        print("Running " + name, flush=True)
        with (folder / (name + ".log")).open("wb") as output:
            result = subprocess.run(command, cwd=cwd, env=env, stdout=output, stderr=subprocess.STDOUT)
        report["steps"].append({"name": name, "exitCode": result.returncode, "startedAt": start.isoformat(), "finishedAt": datetime.now(timezone.utc).isoformat()})
        if result.returncode:
            raise RuntimeError(name + " failed; inspect the private verification log")

    # Do not stop the user's services or silently share their volumes for a test run.
    running = subprocess.check_output(["docker", "ps", "--format", "{{.Label \"com.docker.compose.project\"}}"], text=True)
    if any(line.startswith("capyiot") for line in running.splitlines()):
        raise SystemExit("A CapyIoT environment is running. Stop it before this isolated verification run; its data will be preserved.")
    if os.name == "nt":
        candidates = sorted((Path.home() / ".jdks").glob("jdk-21*"), reverse=True)
        if candidates:
            env["JAVA_HOME"] = str(candidates[0]); env["PATH"] = str(candidates[0] / "bin") + os.pathsep + env["PATH"]
    maven = str(ROOT / "backend" / ("mvnw.cmd" if os.name == "nt" else "mvnw"))
    npm = shutil.which("npm.cmd" if os.name == "nt" else "npm")
    if not npm:
        raise SystemExit("Node.js/npm is required for unified frontend verification")
    try:
        execute("backend", [maven, "-B", "-ntp", "verify"], ROOT / "backend")
        execute("simulator-build", [maven, "-B", "-ntp", "-f", str(ROOT / "simulator/pom.xml"), "verify"], ROOT / "backend")
        execute("frontend-install", [npm, "ci"], ROOT / "frontend")
        for task in ("lint", "typecheck", "build"):
            execute("frontend-" + task, [npm, "run", task], ROOT / "frontend")
        if args.install_browser:
            execute("browser-install", [npm, "exec", "--", "playwright", "install", "--with-deps", "chromium"], ROOT / "frontend")
        execute("helm-lint", ["docker", "run", "--rm", "-v", str(ROOT / "infra/helm/capyiot") + ":/chart:ro", "alpine/helm:3.17.3", "lint", "/chart"])
        execute("helm-render", ["docker", "run", "--rm", "-v", str(ROOT / "infra/helm/capyiot") + ":/chart:ro", "alpine/helm:3.17.3", "template", "capyiot", "/chart"])
        execute("local-credentials", [sys.executable, "scripts/init_env.py"])
        execute("local-tls", [sys.executable, "scripts/init_tls.py"])
        execute("compose-config", ["docker", "compose", "config", "--quiet"])
        # A placeholder ensures Compose sees a file mount even before the provisioner issues credentials.
        devices.write_text("[]", encoding="utf-8")
        launched = True
        execute("compose-start", ["docker", "compose", "up", "-d", "--build", "--wait", "--wait-timeout", "300"])
        execute("video-provision", [sys.executable, "scripts/video_devices.py", "--output", str(devices)])
        execute("mqtt-protocol", [sys.executable, "scripts/video_protocol_smoke.py", "--devices", str(devices)])
        execute("browser", [npm, "run", "test:e2e"], ROOT / "frontend")
        execute("simulator-image", ["docker", "compose", "--profile", "simulation", "build", "simulator"])
        execute("outage-start", ["docker", "compose", "--profile", "simulation", "run", "-d", "--name", simulator, "simulator", "--scenario", "switch-outage", "--interval-ms", "1000", "--fault-seconds", "60"])
        execute("business-workflow", [sys.executable, "scripts/video_workflow_smoke.py"])
        report["status"] = "PASSED"
    except Exception as error:
        report["status"] = "FAILED"
        report["error"] = str(error)
        raise
    finally:
        if launched:
            # Specific containers/project only. No volume deletion and no changes to the original project.
            with (folder / "simulator.log").open("wb") as output:
                subprocess.run(["docker", "logs", "--tail", "200", simulator], cwd=ROOT, env=env, stdout=output, stderr=subprocess.STDOUT)
            subprocess.run(["docker", "stop", simulator], cwd=ROOT, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            with (folder / "compose.log").open("wb") as output:
                subprocess.run(["docker", "compose", "logs", "--tail", "200"], cwd=ROOT, env=env, stdout=output, stderr=subprocess.STDOUT)
            subprocess.run(["docker", "compose", "down"], cwd=ROOT, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        report["finishedAt"] = datetime.now(timezone.utc).isoformat()
        (folder / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print("Private verification report: " + str(folder / "report.json"))


if __name__ == "__main__":
    main()
