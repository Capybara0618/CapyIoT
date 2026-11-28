"""Unified selected CN checks. Failures and their later reruns use separate local reports."""

import argparse
import contextlib
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
from cloud_native_scenarios import dependency_and_grants, health_faults, migration_failure, kafka_recovery, pipeline_handover, reset_scenario_anomalies, resilience, snapshot
from microservices_common import APPS, ROOT, private_path, write_json

GROUPS = ("unit", "build", "manifests", "deploy", "protocol", "workflow", "agent", "real-model", "browser", "resilience", "metrics", "recovery", "upgrade", "release-rehearsal")


@contextlib.contextmanager
def real_provider(target, key_file, model, base_url):
    from urllib.parse import urlsplit
    from cloud_native_manifests import configuration
    import base64
    parsed=urlsplit(base_url)
    if parsed.scheme != "https" or parsed.username or parsed.password or parsed.query or parsed.fragment or not model:
        raise ValueError("Explicit HTTPS model origin and model ID required")
    key=cn.safe_file(private_path(key_file).parent,private_path(key_file).name).read_bytes()
    if not key.strip(): raise ValueError("Real provider key is empty")
    obj=json.loads(cn.execute(target.kubectl("get","deployment","agent-service","-o","json")))
    pod=obj["spec"]["template"]["spec"];container=pod["containers"][0]
    original_config=container["envFrom"][0]["configMapRef"]["name"]
    index=next(i for i,v in enumerate(pod["volumes"]) if v["name"] == "credentials")
    original_secret=pod["volumes"][index]["secret"]["secretName"]
    old=json.loads(cn.execute(target.kubectl("get","secret",original_secret,"-o","json")))
    suffix=uuid.uuid4().hex[:10];new_secret="agent-real-"+suffix;new_config="agent-real-config-"+suffix
    data={**old["data"],"deepseek-api-key":base64.b64encode(key).decode()}
    release.apply(target,release.resources([
        {"apiVersion":"v1","kind":"Secret","immutable":True,"type":"Opaque","metadata":{"name":new_secret,"namespace":cn.NAMESPACE},"data":data},
        {"apiVersion":"v1","kind":"ConfigMap","immutable":True,"metadata":{"name":new_config,"namespace":cn.NAMESPACE},"data":configuration("agent-service",active=True,model={"id":model,"baseUrl":base_url})}]))
    def select(config,secret):
        patch=json.dumps([{"op":"replace","path":"/spec/template/spec/containers/0/envFrom/0/configMapRef/name","value":config},
                          {"op":"replace","path":f"/spec/template/spec/volumes/{index}/secret/secretName","value":secret}])
        cn.execute(target.kubectl("patch","deployment","agent-service","--type=json","-p",patch))
        cn.execute(target.kubectl("rollout","status","deployment/agent-service","--timeout=180s"))
    try:
        select(new_config,new_secret)
        yield
    finally: select(original_config,original_secret)


def offline_provider(target, agent_image):
    if target.manifest["modelMode"] != "offline": return
    # Reuse the release-bound, already imported Python runtime. Base-image indexes
    # may contain unpulled architectures and cannot be imported with --all-platforms.
    script = (ROOT / "agent-service/tests/model_fixture.py").read_text(encoding="utf-8")
    release.apply(target, release.resources([
        {"apiVersion": "v1", "kind": "ConfigMap", "metadata": {"name": "model-fixture", "namespace": cn.NAMESPACE}, "data": {"model_fixture.py": script}},
        {"apiVersion": "apps/v1", "kind": "Deployment", "metadata": {"name": "model-fixture", "namespace": cn.NAMESPACE}, "spec": {
            "replicas": 1, "selector": {"matchLabels": {"app": "model-fixture"}}, "template": {"metadata": {"labels": {"app": "model-fixture"}}, "spec": {
                "automountServiceAccountToken": False, "securityContext": {"runAsNonRoot": True, "runAsUser": 10001},
                    "containers": [{"name": "fixture", "image": agent_image, "imagePullPolicy": "IfNotPresent", "command": ["python", "/fixture/model_fixture.py"],
                    "resources": {"requests": {"memory": "32Mi", "cpu": "25m"}, "limits": {"memory": "128Mi", "cpu": "500m"}},
                    "volumeMounts": [{"name": "fixture", "mountPath": "/fixture", "readOnly": True}]}], "volumes": [{"name": "fixture", "configMap": {"name": "model-fixture"}}]}}}},
        {"apiVersion": "v1", "kind": "Service", "metadata": {"name": "model-fixture", "namespace": cn.NAMESPACE},
         "spec": {"selector": {"app": "model-fixture"}, "ports": [{"port": 8099, "targetPort": 8099}]}}]))


def real_query(target, folder):
    from cloud_native_scenarios import Api
    from urllib.request import Request, urlopen
    api=Api(target)
    before=api.call("/workorders")["total"]
    try:
        conversation=api.call("/agent/conversations",{"title":"CN bounded real model read-only query"})["conversationId"]
        request=Request(api.base+"/agent/conversations/"+conversation+"/messages",
            data=json.dumps({"content":"请查询当前可见设备列表，根据真实查询结果概述设备连接状态，引用证据。只做查询，不准备草稿、不创建工单。"}).encode(),
            headers={"Content-Type":"application/json","Accept":"text/event-stream","Authorization":"Bearer "+api.token,"Idempotency-Key":uuid.uuid4().hex})
        with urlopen(request,context=api.tls,timeout=100) as response: wire=response.read().decode()
        (folder/"real-model-query.sse").write_text(wire,encoding="utf-8")
        events=[json.loads(line[6:]) for line in wire.splitlines() if line.startswith("data: ")]
        if not events: raise AssertionError("No real-model Run events")
        view=api.call("/agent/runs/"+events[0]["runId"])
        if view["status"]!="SUCCEEDED" or not view.get("answer",{}).get("facts") or view.get("draftId") or api.call("/workorders")["total"]!=before:
            raise AssertionError("Real-model read-only evidence query did not complete")
        write_json(folder/"real-model-query.json",{"runId":view["runId"],"status":view["status"],
            "toolCallCount":view["toolCallCount"],"newWorkorders":0,"modelRequestsMaximum":3,"toolCallsMaximum":5})
    finally: api.call("/auth/logout",{})


def verify(args):
    folder = private_path(args.report_dir)
    folder.mkdir(parents=True, exist_ok=False, mode=0o700)
    report = {"status": "RUNNING", "groups": args.groups, "steps": [],
              "commit": subprocess.check_output(["git", "rev-parse", "HEAD"],cwd=ROOT).decode().strip(),
              "startedAt": datetime.now(timezone.utc).isoformat()}
    write_json(folder/"report.json",report)
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
                step("image-" + service,["docker","build","-f","infra/java/Dockerfile","--build-arg","SERVICE=" + service,"--build-arg","CAPYIOT_BUILD_ID=" + args.image_tag,"-t","capyiot/" + service + ":" + args.image_tag,"."])
            for name, file, context in (("agent-service","agent-service/Dockerfile","agent-service"),("frontend","frontend/Dockerfile","frontend"),("simulator","simulator/Dockerfile",".")):
                if name == "frontend" and args.frontend_artifact:
                    from cloud_native_frontend_artifact import package
                    directory,digest=package(args.frontend_artifact,folder/"frontend-image-context")
                    step("image-frontend",["docker","build","--build-arg","CAPYIOT_ARTIFACT_SHA="+digest,"-t","capyiot/frontend:"+args.image_tag,directory])
                else:
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
                offline_provider(target,candidate["images"]["agent-service"])
                release.deploy(target,release.read_release(target,args.release_id))
                release.deploy(target,release.read_release(target,args.release_id),active=True)
            common=["--base-url","https://localhost:28443","--ca-file",target.bundle / "pki/edge/ca.pem","--env-file",credential_file]
            if set(args.groups) & {"protocol","workflow","agent","real-model","metrics"}:
                step("assets",[sys.executable,ROOT / "scripts/video_devices.py",*common,"--prefix","VIDEO-CNVERIFY","--output",target.bundle / "video-devices.json"])
            if "protocol" in args.groups:
                step("protocol",[sys.executable,ROOT / "scripts/video_protocol_smoke.py",*common,"--port","28883","--devices",target.bundle / "video-devices.json"])
            for selected_group,fault_seconds in (("workflow",60),("agent",300),("real-model",90)):
                if selected_group not in args.groups: continue
                reset_scenario_anomalies(target,folder)
                name="capyiot-cn-sim-" + uuid.uuid4().hex[:10]
                sim=cn.execute(["docker","run","-d","--name",name,"--network",cn.BRIDGE,"--add-host","frontend:" + cn.NODE_IP,
                    "--label","io.capyiot.cn.owner=" + target.manifest["ownerId"],"--label","io.capyiot.cn.role=simulator",
                    "--memory","256m","-v",str(target.bundle / "video-devices.json") + ":/devices.json:ro",
                    "-v",str(target.bundle / "pki/edge/ca.pem") + ":/ca.pem:ro","capyiot/simulator:" + args.image_tag,
                    "--api-url","https://frontend:30443","--mqtt-url","ssl://emqx:8883","--ca-file","/ca.pem","--devices","/devices.json",
                    "--scenario","switch-outage","--fault-seconds",str(fault_seconds),"--interval-ms","1000"]).decode().strip()
                try:
                    if selected_group == "workflow": step("workflow",[sys.executable,ROOT / "scripts/video_workflow_smoke.py",*common])
                    if selected_group == "agent":
                        for script in ("agent_foundation_smoke","agent_query_smoke","agent_draft_smoke"):
                            step(script,[sys.executable,ROOT / ("scripts/" + script + ".py")])
                    if selected_group == "real-model":
                        if not args.real_key_file: raise ValueError("Explicit private real-model key file required")
                        with real_provider(target,args.real_key_file,args.real_model,args.real_base_url):
                            real_query(target,folder)
                            step("real-model-workflow",[sys.executable,ROOT / "scripts/video_workflow_smoke.py",*common,"--agent","--real-model","--evidence-dir",folder])
                finally: cn.execute(["docker","stop",sim])
            if "browser" in args.groups:
                env["CAPYIOT_SCREENSHOT_DIR"] = str(folder/"screenshots")
                step("browser",[npm,"run","test:e2e","--","--output",folder/"playwright"],ROOT / "frontend")
            if "release-rehearsal" in args.groups:
                from cloud_native_scenarios import Api
                api=Api(target)
                before={r["workOrderId"] for r in api.call("/workorders?size=100")["items"]}
                api.call("/auth/logout",{})
                previous=json.loads((target.bundle/"active-release.json").read_text())["releaseId"]
                release.prepare_release(target,args.release_id,args.image_tag)
                release.migrate(target,release.read_release(target,args.release_id))
                release.deploy(target,release.read_release(target,args.release_id))
                release.deploy(target,release.read_release(target,args.release_id),active=True)
                release.rollback(target,release.read_release(target,args.release_id))
                current=json.loads((target.bundle/"active-release.json").read_text())["releaseId"]
                api=Api(target)
                try:
                    after={r["workOrderId"] for r in api.call("/workorders?size=100")["items"]}
                    if current!=previous or before!=after: raise AssertionError("Compatible rollback changed authority or work-order identity")
                    write_json(folder/"compatible-rollback.json",{"previousRelease":previous,"candidateRelease":args.release_id,
                        "activeAfterRollback":current,"workorderIdsPreserved":sorted(after),"databaseAndOffsetsRewound":False})
                finally: api.call("/auth/logout",{})
            if "resilience" in args.groups:
                for name,check in (("dependency-and-grants",dependency_and_grants),("health-faults",health_faults),
                                   ("migration-failure",migration_failure),("pipeline-handover",pipeline_handover),
                                   ("pod-and-rollout",lambda t,f:resilience(t,f,args.rollout_tag)),("kafka-recovery",kafka_recovery)):
                    print("CN scenario: "+name,flush=True)
                    check(target,folder)
                    report["steps"].append({"name":name,"exitCode":0})
                    write_json(folder/"report.json",report)
            if "metrics" in args.groups:
                if not args.policy: raise ValueError("Frozen local measurement policy is required")
                step("measurements",[sys.executable,ROOT / "scripts/measure_microservices.py",
                     "--devices",target.bundle / "video-devices.json","--policy",args.policy,
                     "--kafka-pause-seconds","20","--report-dir",folder / "measurements"])
                summary=json.loads(cn.execute(target.kubectl("get","--raw","/api/v1/nodes/" + cn.CLUSTER + "-control-plane/proxy/stats/summary")))
                summary["pods"]=[p for p in summary.get("pods",[]) if p.get("podRef",{}).get("namespace") == cn.NAMESPACE]
                write_json(folder / "kubelet-resources.json",summary)
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
            if "upgrade" in args.groups:
                if not args.upgrade_backup: raise ValueError("Explicit pre-CN backup required")
                from cloud_native_upgrade import upgrade_copy
                upgrade_copy(target,args.upgrade_backup,folder/"upgrade")
        report["status"]="PASSED_SELECTED_GROUPS"
    except KeyboardInterrupt:
        report["status"]="INTERRUPTED"
        raise
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
    parser.add_argument("--rollout-tag")
    parser.add_argument("--real-key-file",type=Path)
    parser.add_argument("--real-model")
    parser.add_argument("--real-base-url",default="https://api.deepseek.com")
    parser.add_argument("--frontend-artifact",type=Path)
    parser.add_argument("--upgrade-backup",type=Path)
    for name in ("cluster","context","namespace","infra-project"): parser.add_argument("--" + name,required=True)
    args=parser.parse_args()
    cn.validate_selection(args.cluster,args.context,args.namespace,args.infra_project)
    verify(args)


if __name__ == "__main__": main()
