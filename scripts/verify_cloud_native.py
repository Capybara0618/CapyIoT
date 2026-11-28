"""Unified selected CN checks. Failures and their later reruns use separate local reports."""

import argparse
import json
import os
import shutil
import subprocess
import sys
import uuid
from datetime import datetime, timezone
from pathlib import Path

import cloud_native_common as cn
import cloud_native_release as release
from cloud_native_environment import verified_endpoints
from cloud_native_scenarios import kafka_recovery, metrics, resilience, snapshot
from microservices_common import APPS, ROOT, private_path, write_json

GROUPS = ("unit", "build", "manifests", "deploy", "protocol", "workflow", "agent", "browser", "resilience", "metrics", "recovery")


def offline_provider(target):
    if target.manifest["modelMode"] != "offline": return
    script = (ROOT / "agent-service/tests/model_fixture.py").read_text()
    release.apply(target, release.resources([
        {"apiVersion": "v1", "kind": "ConfigMap", "metadata": {"name": "model-fixture", "namespace": cn.NAMESPACE}, "data": {"model_fixture.py": script}},
        {"apiVersion": "apps/v1", "kind": "Deployment", "metadata": {"name": "model-fixture", "namespace": cn.NAMESPACE}, "spec": {
            "replicas": 1, "selector": {"matchLabels": {"app": "model-fixture"}}, "template": {"metadata": {"labels": {"app": "model-fixture"}}, "spec": {
                "automountServiceAccountToken": False, "securityContext": {"runAsNonRoot": True, "runAsUser": 10001},
                "containers": [{"name": "fixture", "image": "python:3.12.12-slim-bookworm", "command": ["python", "/fixture/model_fixture.py"],
                    "resources": {"requests": {"memory": "32Mi", "cpu": "25m"}, "limits": {"memory": "128Mi", "cpu": "500m"}},
                    "volumeMounts": [{"name": "fixture", "mountPath": "/fixture", "readOnly": True}]}], "volumes": [{"name": "fixture", "configMap": {"name": "model-fixture"}}]}}}},
        {"apiVersion": "v1", "kind": "Service", "metadata": {"name": "model-fixture", "namespace": cn.NAMESPACE},
         "spec": {"selector": {"app": "model-fixture"}, "ports": [{"port": 8099, "targetPort": 8099}]}}]))


def verify(args):
    folder = private_path(args.report_dir)
    folder.mkdir(parents=True, exist_ok=False, mode=0o700)
    report = {"status": "RUNNING", "groups": args.groups, "steps": [],
              "commit": subprocess.check_output(["git", "rev-parse", "HEAD"],cwd=ROOT).decode().strip(),
              "startedAt": datetime.now(timezone.utc).isoformat()}
    env = dict(os.environ)
    for key in list(env):
        if key.startswith(("CAPYIOT_MS_", "COMPOSE_")): env.pop(key)
    jdks = sorted((Path.home() / ".jdks").glob("jdk-21*"))
    if jdks:
        env["JAVA_HOME"] = str(jdks[-1]); env["PATH"] = str(jdks[-1] / "bin") + os.pathsep + env["PATH"]
    maven = ROOT / "backend" / ("mvnw.cmd" if os.name == "nt" else "mvnw")
    npm = shutil.which("npm.cmd" if os.name == "nt" else "npm")
    def step(name, command, cwd=ROOT):
        print("CN check: " + name, flush=True)
        argv = list(map(str, command))
        if os.name == "nt" and argv[0].endswith(".cmd"):
            argv = [os.environ.get("COMSPEC", "cmd.exe"), "/d", "/c", *argv]
        with (folder / (name + ".log")).open("xb") as stream:
            value = subprocess.run(argv,cwd=cwd,env=env,stdout=stream,stderr=subprocess.STDOUT)
        report["steps"].append({"name": name, "exitCode": value.returncode})
        write_json(folder / "report.json",report)
        if value.returncode: raise RuntimeError("Selected check failed: " + name)
    try:
        if "unit" in args.groups:
            step("backend",[maven,"-B","-ntp","--fail-at-end","clean","verify"],ROOT / "backend")
            step("simulator",[maven,"-B","-ntp","-f",ROOT / "simulator/pom.xml","verify"],ROOT / "backend")
            step("agent-lint",["uv","run","--project","agent-service","--no-sync","ruff","check","agent-service/app","agent-service/tests"])
            step("agent-tests",["uv","run","--no-sync","pytest","-q"],ROOT / "agent-service")
            step("deployment-tests",[sys.executable,"-m","unittest","discover","-s","scripts/tests"])
            for script in ("test:agent","lint","typecheck","build"):
                step("frontend-" + script.replace(":","-"),[npm,"run",script],ROOT / "frontend")
        if "build" in args.groups:
            if not args.image_tag: raise ValueError("Image tag required")
            for service in APPS[:-1]:
                step("image-" + service,["docker","build","-f","infra/java/Dockerfile","--build-arg","SERVICE=" + service,"-t","capyiot/" + service + ":" + args.image_tag,"."])
            for name, file, context in (("agent-service","agent-service/Dockerfile","agent-service"),("frontend","frontend/Dockerfile","frontend"),("simulator","simulator/Dockerfile",".")):
                step("image-" + name,["docker","build","-f",file,"-t","capyiot/" + name + ":" + args.image_tag,context])
        if "manifests" in args.groups:
            step("kustomize",["kubectl","kustomize","infra/k8s/overlays/verify"])
        runtime = set(args.groups) - {"unit","build","manifests"}
        if runtime:
            target = cn.Target(args.bundle,cluster=args.cluster,context=args.context,namespace=args.namespace,infra_project=args.infra_project)
            verified_endpoints(target)
            credential_file = folder / "scenario.env"
            credential_file.write_text("".join(k + "=" + (target.bundle / "secrets" / k.lower().replace("_","-")).read_text() + "\n" for k in ("DEMO_ADMIN_PASSWORD","DEMO_OPERATOR_PASSWORD")))
            credential_file.chmod(0o600)
            selected = {"bundle": str(target.bundle),"cluster": cn.CLUSTER,"context": cn.CONTEXT,"namespace": cn.NAMESPACE,"infra_project": cn.INFRA_PROJECT}
            env.update(CAPYIOT_CN_TARGET=json.dumps(selected), CAPYIOT_CREDENTIAL_FILE=str(credential_file),
                       CAPYIOT_CA_FILE=str(target.bundle / "pki/edge/ca.pem"),CAPYIOT_API_BASE="https://localhost:28443/api/v1",
                       CAPYIOT_BASE_URL="http://127.0.0.1:28088",CAPYIOT_AGENT_LIVE_TESTS="true")
            if "deploy" in args.groups:
                release.prepare_release(target,args.release_id,args.image_tag)
                candidate=release.read_release(target,args.release_id)
                release.migrate(target,candidate)
                offline_provider(target)
                release.deploy(target,release.read_release(target,args.release_id))
                release.deploy(target,release.read_release(target,args.release_id),active=True)
            common=["--base-url","https://localhost:28443","--ca-file",target.bundle / "pki/edge/ca.pem","--env-file",credential_file]
            if set(args.groups) & {"protocol","workflow","agent","metrics"}:
                step("assets",[sys.executable,ROOT / "scripts/video_devices.py",*common,"--prefix","CNVERIFY","--output",target.bundle / "video-devices.json"])
            if "protocol" in args.groups:
                step("protocol",[sys.executable,ROOT / "scripts/video_protocol_smoke.py",*common,"--port","28883","--devices",target.bundle / "video-devices.json"])
            if set(args.groups) & {"workflow","agent"}:
                name="capyiot-cn-sim-" + uuid.uuid4().hex[:10]
                sim=cn.execute(["docker","run","-d","--name",name,"--network",cn.BRIDGE,"--add-host","frontend:" + cn.NODE_IP,
                    "--label","io.capyiot.cn.owner=" + target.manifest["ownerId"],"--label","io.capyiot.cn.role=simulator",
                    "--memory","256m","-v",str(target.bundle / "video-devices.json") + ":/devices.json:ro",
                    "-v",str(target.bundle / "pki/edge/ca.pem") + ":/ca.pem:ro","capyiot/simulator:" + args.image_tag,
                    "--api-url","https://frontend:30443","--mqtt-url","ssl://emqx:8883","--ca-file","/ca.pem","--devices","/devices.json",
                    "--scenario","switch-outage","--fault-seconds","300","--interval-ms","1000"]).decode().strip()
                try:
                    if "workflow" in args.groups: step("workflow",[sys.executable,ROOT / "scripts/video_workflow_smoke.py",*common])
                    if "agent" in args.groups:
                        for script in ("agent_foundation_smoke","agent_query_smoke","agent_draft_smoke"):
                            step(script,[sys.executable,ROOT / ("scripts/" + script + ".py")])
                finally: cn.execute(["docker","stop",sim])
            if "browser" in args.groups: step("browser",[npm,"run","test:e2e"],ROOT / "frontend")
            if "resilience" in args.groups: resilience(target,folder); kafka_recovery(target,folder)
            if "metrics" in args.groups:
                if not args.policy: raise ValueError("Frozen local measurement policy is required")
                step("measurements",[sys.executable,ROOT / "scripts/measure_microservices.py",
                     "--devices",target.bundle / "video-devices.json","--policy",args.policy,
                     "--report-dir",folder / "measurements"])
            if "recovery" in args.groups:
                backup=snapshot(target,folder / "backup")
                restore_project="capyiot-ms-verify-cn-restore-" + uuid.uuid4().hex[:8]
                restore_bundle=ROOT / "var/bundles" / restore_project
                step("restore-init",[sys.executable,ROOT / "scripts/init_microservices.py","--project",restore_project,"--bundle",restore_bundle,"--mode","fresh"])
                try:
                    step("restore-rehearsal",[sys.executable,ROOT / "scripts/microservices_control.py","restore-rehearsal","--project",restore_project,"--compose-file",ROOT / "compose.microservices.yml","--env-file",restore_bundle / "deployment.env","--report-dir",folder / "restore","--backup-dir",backup,"--execute"])
                finally:
                    from microservices_common import Target as MsTarget, command_env
                    restore=MsTarget(restore_project,ROOT / "compose.microservices.yml",restore_bundle / "deployment.env",verification=True)
                    subprocess.run(restore.compose("stop"),env=command_env(restore.compose("stop")),stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        report["status"]="PASSED_SELECTED_GROUPS"
    except Exception as error:
        report["status"]="FAILED"; report["errorCategory"]=type(error).__name__
        raise
    finally:
        report["finishedAt"]=datetime.now(timezone.utc).isoformat()
        write_json(folder / "report.json",report)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--groups",nargs="+",choices=GROUPS,required=True)
    parser.add_argument("--bundle",type=Path,required=True)
    parser.add_argument("--report-dir",type=Path,required=True)
    parser.add_argument("--image-tag")
    parser.add_argument("--release-id",default="r-initial")
    parser.add_argument("--policy",type=Path)
    for name in ("cluster","context","namespace","infra-project"): parser.add_argument("--" + name,required=True)
    args=parser.parse_args()
    cn.validate_selection(args.cluster,args.context,args.namespace,args.infra_project)
    verify(args)


if __name__ == "__main__": main()
