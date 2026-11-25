"""One explicit, sequential stage-one verification run after all coding is complete.

Uses separate Compose volumes and a separate device credential file. Does not run on push.
Raw logs and reports stay under ignored var/verification/. No measurements are fabricated.
"""

import json
import argparse
import hashlib
import os
import shutil
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--install-browser",action="store_true")
    parser.add_argument("--resume-report",type=Path,help="Reuse successful build checks only when their input files are unchanged")
    args=parser.parse_args()
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    folder = ROOT / "var" / "verification" / stamp
    folder.mkdir(parents=True)
    env = os.environ.copy()
    project = "capyiot-stage1-" + stamp.lower()
    env["COMPOSE_PROJECT_NAME"] = project
    # The isolated acceptance stack does not start the optional monitoring profile.
    env["OTEL_EXPORTER_OTLP_TRACES_ENDPOINT"] = ""
    devices = folder / "video-devices.json"
    env["VIDEO_DEVICE_FILE"] = devices.as_posix()
    report = {"startedAt": stamp, "composeProject": project, "steps": [], "status": "RUNNING"}
    simulator = project + "-simulator"
    launched = False
    previous=json.loads(args.resume_report.read_text(encoding="utf-8")) if args.resume_report else {}
    if args.resume_report:report["resumedFrom"]=str(args.resume_report.resolve())
    reusable={step["name"]:step for step in previous.get("steps",[]) if step.get("exitCode")==0}
    def input_hash(name):
        if name=="backend": prefixes=("backend/","infra/emqx/")
        elif name=="simulator-build":prefixes=("simulator/","backend/pom.xml")
        elif name.startswith("frontend-") or name=="browser-install":prefixes=("frontend/",)
        elif name.startswith("helm-"):prefixes=("infra/helm/",)
        else:return None # Runtime checks always execute against their new isolated environment.
        names=subprocess.check_output(["git","ls-files","--cached","--others","--exclude-standard"],cwd=ROOT,text=True).splitlines()
        digest=hashlib.sha256()
        for path in sorted(set(names)):
            if path.startswith(prefixes) and (ROOT/path).is_file():
                digest.update(path.encode());digest.update((ROOT/path).read_bytes())
        return digest.hexdigest()

    def execute(name, command, cwd=ROOT):
        start = datetime.now(timezone.utc)
        fingerprint=input_hash(name)
        old=reusable.get(name)
        if fingerprint and old and old.get("inputHash")==fingerprint:
            report["steps"].append({**old,"reusedFrom":str(args.resume_report.resolve())})
            print("Reusing unchanged successful check: "+name,flush=True)
            return
        if os.name == "nt" and str(command[0]).endswith(".cmd"):
            command = [os.environ.get("COMSPEC", "cmd.exe"), "/d", "/c", *map(str, command)]
        print("Running " + name, flush=True)
        with (folder / (name + ".log")).open("wb") as output:
            result = subprocess.run(command, cwd=cwd, env=env, stdout=output, stderr=subprocess.STDOUT)
        report["steps"].append({"name": name, "exitCode": result.returncode,"inputHash":fingerprint, "startedAt": start.isoformat(), "finishedAt": datetime.now(timezone.utc).isoformat()})
        (folder/"report.json").write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf-8")
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
        time.sleep(1)
        if subprocess.check_output(["docker", "inspect", "--format", "{{.State.Running}}", simulator], text=True).strip() != "true":
            raise RuntimeError("Simulator exited during startup; inspect the private simulator log")
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
