"""Real scoped Kubernetes failures, API sampling and frozen SQL recovery evidence."""

import json
import math
import ssl
import statistics
import time
import uuid
import threading
import socket
import struct
from datetime import datetime, timezone
from concurrent.futures import ThreadPoolExecutor
from urllib.request import Request, urlopen

import cloud_native_common as cn
from cloud_native_environment import verified_endpoints
from cloud_native_manifests import APPS
from microservices_common import SCHEMAS, sha, write_json
from microservices_control import PASSWORD, db, inventory, offsets


def wait(predicate, seconds=180):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if predicate(): return
        time.sleep(1)
    raise TimeoutError("Frozen CN recovery budget exceeded")


class Api:
    def __init__(self, target):
        self.base = "https://localhost:28443/api/v1"
        self.tls = ssl.create_default_context(cafile=target.bundle / "pki/edge/ca.pem")
        self.token = None
        self.last_headers = {}
        self.call("/auth/login", {"loginName": "supervisor", "password": (target.bundle / "secrets/demo-admin-password").read_text()}, login=True)

    def call(self, path, body=None, *, login=False, key=None):
        headers = {"Content-Type": "application/json", "X-Correlation-Id": uuid.uuid4().hex}
        if self.token: headers["Authorization"] = "Bearer " + self.token
        if body is not None: headers["Idempotency-Key"] = key or uuid.uuid4().hex
        request = Request(self.base + path, data=None if body is None else json.dumps(body).encode(), headers=headers)
        with urlopen(request, context=self.tls, timeout=15) as response:
            self.last_headers = dict(response.headers)
            value = json.load(response)
            if value["code"] != "OK": raise ValueError("Business API returned a failed envelope")
            if login: self.token = value["data"]["accessToken"]
            return value["data"]


def distribution(values):
    values = sorted(values)
    if not values: raise ValueError("No measured samples")
    return {"samples": len(values), "p50Ms": statistics.median(values),
            **{"p" + str(p) + "Ms": values[math.ceil(len(values) * p / 100) - 1] for p in (95, 99)},
            "method": "nearest-rank; p50 median"}


def metrics(target, folder):
    api = Api(target)
    device = next(d for d in json.loads((target.bundle / "video-devices.json").read_text()) if d["deviceType"] == "IPC")
    measured = {}
    for path in ("/devices", "/incidents", "/workorders", "/topology/context?deviceId=" + device["deviceId"]):
        for _ in range(10): api.call(path)
        def sample(_):
            start = time.perf_counter()
            try: api.call(path); return (time.perf_counter() - start) * 1000, True
            except Exception: return (time.perf_counter() - start) * 1000, False
        with ThreadPoolExecutor(max_workers=4) as pool: rows = list(pool.map(sample, range(120)))
        measured[path.split("?")[0]] = {**distribution([x[0] for x in rows]), "errors": sum(not x[1] for x in rows), "rawMs": [x[0] for x in rows]}
    write_json(folder / "api-metrics.json", {"results": measured, "productionSlaClaimed": False})
    if any(x["errors"] for x in measured.values()): raise AssertionError("API measurement has failed samples")


def resilience(target, folder, rollout_tag):
    verified_endpoints(target)
    api = Api(target)
    results = []
    for service in APPS:
        pods = json.loads(cn.execute(target.kubectl("get", "pods", "-l", "app.kubernetes.io/name=" + service, "-o", "json")))["items"]
        old_uid = pods[0]["metadata"]["uid"]
        start = time.monotonic()
        cn.execute(target.kubectl("delete", "pod", pods[0]["metadata"]["name"], "--wait=true", "--timeout=90s"))
        cn.execute(target.kubectl("rollout", "status", "deployment/" + service, "--timeout=180s"))
        replacement = json.loads(cn.execute(target.kubectl("get", "pods", "-l", "app.kubernetes.io/name=" + service, "-o", "json")))["items"]
        if not replacement or replacement[0]["metadata"]["uid"] == old_uid: raise AssertionError("No replacement Pod")
        api.call({"alarm-service":"/incidents","workflow-service":"/workorders","platform-service":"/auth/me",
                  "agent-service":"/agent/conversations"}.get(service,"/devices"))
        elapsed = time.monotonic() - start
        results.append({"service": service, "oldUid": old_uid, "newUid": replacement[0]["metadata"]["uid"], "seconds": elapsed})
        write_json(folder / "pod-recovery.json", {"budgetSeconds": 180, "results": results})
        if elapsed > 180: raise AssertionError("Pod recovery exceeded frozen budget")
    for service in ("gateway", "platform-service"):
        cn.execute(target.kubectl("scale", "deployment/" + service, "--replicas=2"))
        try:
            cn.execute(target.kubectl("rollout", "status", "deployment/" + service, "--timeout=180s"))
            pods = json.loads(cn.execute(target.kubectl("get", "pods", "-l", "app.kubernetes.io/name=" + service, "-o", "json")))["items"]
            if len(pods) != 2: raise AssertionError("Two replicas not observed")
            seen = set()
            header = "X-Gateway-Instance" if service == "gateway" else "X-Service-Instance"
            # Concurrent connections exercise Service balancing; a single pooled connection can stay pinned.
            def probe_replica(_):
                client=Api(target)
                client.call("/auth/me")
                return next((v for k,v in client.last_headers.items() if k.lower() == header.lower()),"unknown")
            with ThreadPoolExecutor(max_workers=8) as pool:
                seen.update(pool.map(probe_replica,range(24)))
            if len(seen - {"unknown"}) != 2: raise AssertionError("Both replicas must serve authenticated traffic")
        finally:
            cn.execute(target.kubectl("scale", "deployment/" + service, "--replicas=1"))
            cn.execute(target.kubectl("rollout", "status", "deployment/" + service, "--timeout=180s"))
    write_json(folder / "replica-recovery.json", {"services": ["gateway", "platform-service"], "replicas": [1, 2, 1]})
    force_process_exit(target,folder)
    agent_process_exit(target,folder)
    rolling_updates(target,folder,rollout_tag)


def force_process_exit(target, folder):
    service = "device-service"
    pod = json.loads(cn.execute(target.kubectl("get","pods","-l","app.kubernetes.io/name=" + service,"-o","json")))["items"][0]
    before = pod["status"]["containerStatuses"][0]["restartCount"]
    node = target.state()["nodeId"]
    containers = json.loads(cn.execute(["docker","exec",node,"crictl","ps","--label","io.kubernetes.pod.name=" + pod["metadata"]["name"],"-o","json"]))["containers"]
    container = next(x for x in containers if x["metadata"]["name"] == service)
    orders_before = Api(target).call("/workorders")["total"]
    started = time.monotonic()
    cn.execute(["docker","exec",node,"ctr","-n","k8s.io","tasks","kill","--signal","SIGKILL",container["id"]])
    def recovered():
        current = json.loads(cn.execute(target.kubectl("get","pod",pod["metadata"]["name"],"-o","json")))
        return any(x.get("ready") and x["restartCount"] > before for x in current.get("status",{}).get("containerStatuses",[]))
    wait(recovered)
    if Api(target).call("/workorders")["total"] != orders_before: raise AssertionError("Process restart changed work-order count")
    write_json(folder / "sigkill-recovery.json",{"service":service,"podUid":pod["metadata"]["uid"],
        "signal":"SIGKILL","containerId":container["id"],"seconds":time.monotonic()-started})


def agent_process_exit(target, folder):
    """Kill an active Run, then prove background lease recovery without a history GET."""
    api=Api(target);state=target.state();node=state["nodeId"]
    mysql=state["containers"]["mysql"]["containerId"]
    def container(label,name):
        pod=json.loads(cn.execute(target.kubectl("get","pods","-l",label,"-o","json")))["items"][0]
        rows=json.loads(cn.execute(["docker","exec",node,"crictl","ps","--label","io.kubernetes.pod.name="+pod["metadata"]["name"],"-o","json"]))["containers"]
        return pod,next(x["id"] for x in rows if x["metadata"]["name"]==name)
    fixture,fixture_id=container("app=model-fixture","fixture")
    agent,agent_id=container("app.kubernetes.io/name=agent-service","agent-service")
    orders=api.call("/workorders")["total"]
    conversation=api.call("/agent/conversations",{"title":"CN active Run crash recovery"})["conversationId"]
    stream=None;run_id=None;started=time.monotonic()
    cn.execute(["docker","exec",node,"ctr","-n","k8s.io","tasks","pause",fixture_id])
    try:
        request=Request(api.base+"/agent/conversations/"+conversation+"/messages",data=json.dumps({"content":"查询设备当前状态"}).encode(),
                        headers={"Content-Type":"application/json","Accept":"text/event-stream","Authorization":"Bearer "+api.token,"Idempotency-Key":uuid.uuid4().hex})
        stream=urlopen(request,context=api.tls,timeout=20)
        while run_id is None:
            line=stream.readline().decode()
            if not line: raise AssertionError("SSE ended before run.started")
            if line.startswith("data:"): run_id=json.loads(line[5:])["runId"]
        if not re_uuid(run_id): raise ValueError("Unexpected Run identifier")
        statement="SELECT status FROM agent_db.agent_run WHERE run_id='"+run_id+"'"
        wait(lambda:db(mysql,statement).strip()=="RUNNING",15)
        cn.execute(["docker","exec",node,"ctr","-n","k8s.io","tasks","kill","--signal","SIGKILL",agent_id])
    finally:
        cn.execute(["docker","exec",node,"ctr","-n","k8s.io","tasks","resume",fixture_id])
        if stream is not None: stream.close()
    def recovered():
        current=json.loads(cn.execute(target.kubectl("get","pod",agent["metadata"]["name"],"-o","json")))
        return any(c.get("ready") and c.get("restartCount",0)>0 for c in current.get("status",{}).get("containerStatuses",[]))
    wait(recovered)
    wait(lambda:db(mysql,statement).strip()=="TIMED_OUT",150)
    view=api.call("/agent/runs/"+run_id)
    if view["errorCode"]!="RUN_LEASE_EXPIRED" or view["toolCallCount"]!=0 or api.call("/workorders")["total"]!=orders:
        raise AssertionError("Crashed Run was replayed or changed business work")
    write_json(folder/"agent-crash-recovery.json",{"runId":run_id,"podUid":agent["metadata"]["uid"],"signal":"SIGKILL",
        "terminal":view["status"],"errorCode":view["errorCode"],"backgroundRecoveryBeforeHistoryRead":True,
        "toolsReplayed":0,"newWorkorders":0,"seconds":time.monotonic()-started})


def re_uuid(value):
    try: return str(uuid.UUID(value))==value
    except (ValueError,TypeError,AttributeError): return False


def rolling_updates(target, folder, image_tag):
    if not image_tag: raise ValueError("A separately built rollout image tag is required")
    for service in ("gateway","platform-service"):
        deployment = json.loads(cn.execute(target.kubectl("get","deployment",service,"-o","json")))
        old_image = deployment["spec"]["template"]["spec"]["containers"][0]["image"]
        new_image = "capyiot/" + service + ":" + image_tag
        old_id=json.loads(cn.execute(["docker","image","inspect",old_image]))[0]["Id"]
        new_id=json.loads(cn.execute(["docker","image","inspect",new_image]))[0]["Id"]
        if old_id == new_id: raise ValueError("Rollout must use an actual different immutable image")
        cn.execute([target.manifest["kindBinary"],"load","docker-image","--name",cn.CLUSTER,new_image])
        samples, stopped = [], threading.Event()
        api=Api(target)
        def probe():
            started=time.monotonic()
            while not stopped.is_set() or len(samples)<180 or time.monotonic()-started<180:
                at=time.monotonic()
                try:
                    api.call("/auth/me")
                    result={"at":at,"ok":True,"instances":{k:v for k,v in api.last_headers.items() if k.lower() in {"x-service-instance","x-gateway-instance"}}}
                except Exception as error: result={"at":at,"ok":False,"category":type(error).__name__}
                samples.append(result)
                stopped.wait(1) if not stopped.is_set() else time.sleep(1)
        thread=threading.Thread(target=probe,daemon=True);thread.start()
        try:
            cn.execute(target.kubectl("set","image","deployment/"+service,service+"="+new_image))
            cn.execute(target.kubectl("rollout","status","deployment/"+service,"--timeout=180s"))
            bad_patch=json.dumps([{"op":"replace","path":"/spec/template/spec/containers/0/image","value":"capyiot/"+service+":absent-cn-image"},
                                  {"op":"replace","path":"/spec/template/spec/containers/0/imagePullPolicy","value":"Never"}])
            cn.execute(target.kubectl("patch","deployment",service,"--type=json","-p",bad_patch))
            time.sleep(10)
            bad=json.loads(cn.execute(target.kubectl("get","pods","-l","app.kubernetes.io/name="+service,"-o","json")))["items"]
            if not any(x.get("state",{}).get("waiting",{}).get("reason") == "ErrImageNeverPull" for p in bad for x in p.get("status",{}).get("containerStatuses",[])):
                raise AssertionError("Failed image was not observed blocked")
        finally:
            revert=json.dumps([{"op":"replace","path":"/spec/template/spec/containers/0/image","value":old_image},
                               {"op":"replace","path":"/spec/template/spec/containers/0/imagePullPolicy","value":"IfNotPresent"}])
            cn.execute(target.kubectl("patch","deployment",service,"--type=json","-p",revert))
            cn.execute(target.kubectl("rollout","status","deployment/"+service,"--timeout=180s"))
            stopped.set();thread.join(timeout=210)
            write_json(folder / (service+"-rolling.json"),{"oldImageId":old_id,"newImageId":new_id,"samples":samples,"minimumWindowSeconds":180})
        if thread.is_alive() or len(samples)<180 or any(not x["ok"] for x in samples):
            raise AssertionError("Rolling/failed-image/rollback continuity did not meet the frozen gate")


def pipeline_handover(target, folder):
    from video_protocol_smoke import encoded, packet, receive
    verified_endpoints(target)
    mysql=target.state()["containers"]["mysql"]["containerId"]
    results=[]
    for fault in ("consumer","ingestion"):
        device=next(x for x in json.loads((target.bundle/"video-devices.json").read_text()) if x["deviceType"] == "IPC")
        session=str(uuid.uuid4()); api=Api(target)
        identity=api.call("/device-sessions",{"deviceId":device["deviceId"],"secret":device["deviceSecret"],"sessionId":session})
        connection=api.tls.wrap_socket(socket.create_connection(("localhost",28883),timeout=10),server_hostname="localhost")
        connection.settimeout(10)
        connection.sendall(packet(0x10,encoded("MQTT")+bytes([4,0xC2])+struct.pack("!H",30)+encoded(device["deviceId"])+encoded(device["deviceId"])+encoded(identity["sessionToken"])))
        kind,body=receive(connection)
        if kind != 0x20 or body[-1:] != b"\0": raise AssertionError("Publisher CONNECT failed")
        service="device-service" if fault == "consumer" else "device-ingestion"
        ids=[]; started=time.monotonic()
        cn.execute(target.kubectl("scale","deployment/"+service,"--replicas=0"))
        cn.execute(target.kubectl("wait","--for=delete","pod","-l","app.kubernetes.io/name="+service,"--timeout=90s"))
        try:
            for sequence in range(1,21):
                event_id=str(uuid.uuid4());ids.append(event_id)
                event={"eventId":event_id,"schemaVersion":1,"deviceId":device["deviceId"],"sessionId":session,
                       "eventType":"HEARTBEAT","streamKey":"HEARTBEAT","sequence":sequence,
                       "occurredAt":datetime.now(timezone.utc).isoformat().replace("+00:00","Z"),"payload":{"bootId":session}}
                topic=f"capyiot/v1/devices/{device['deviceId']}/sessions/{session}/events"
                connection.sendall(packet(0x32,encoded(topic)+struct.pack("!H",sequence)+json.dumps(event).encode()))
                kind,body=receive(connection)
                if kind != 0x40: raise AssertionError("Publisher PUBACK missing")
        finally:
            connection.close()
            cn.execute(target.kubectl("scale","deployment/"+service,"--replicas=1"))
            cn.execute(target.kubectl("rollout","status","deployment/"+service,"--timeout=180s"))
        quoted=",".join("'"+x+"'" for x in ids)
        def applied():
            return db(mysql,"SELECT COUNT(*) FROM device_db.device_event_receipt WHERE event_id IN ("+quoted+") AND processing_status='APPLIED';").strip() == "20"
        wait(applied,120)
        results.append({"fault":fault,"eventIds":ids,"uniqueApplied":20,"seconds":time.monotonic()-started})
        write_json(folder/"pipeline-handover.json",{"results":results,"brokerStayedOnline":True})


def dependency_and_grants(target, folder):
    verified_endpoints(target)
    state=target.state();mysql=state["containers"]["mysql"]["containerId"]
    grants=[]
    for owner in ("device","alarm","workflow","platform","agent"):
        for schema in ("device","alarm","workflow","platform","agent"):
            # SHOW/TABLE access is tested directly; information_schema alone silently hides inaccessible rows.
            table="alembic_version" if schema == "agent" else "flyway_schema_history"
            statement=f"SELECT COUNT(*) FROM {schema}_db.{table}"
            command=f'MYSQL_PWD="$(cat /run/secrets/{owner}-db-password)" exec mysql -u{owner}_app -N -e "{statement}"'
            argv=["docker","exec",mysql,"sh","-c",command]
            try: cn.execute(argv); allowed=True
            except RuntimeError: allowed=False
            if allowed != (owner == schema): raise AssertionError("Schema access boundary failed")
            grants.append({"owner":owner,"schema":schema,"allowed":allowed})
    api=Api(target)
    cn.execute(target.kubectl("scale","deployment/platform-service","--replicas=0"))
    cn.execute(target.kubectl("wait","--for=delete","pod","-l","app.kubernetes.io/name=platform-service","--timeout=90s"))
    try:
        from urllib.error import HTTPError
        try: api.call("/devices"); raise AssertionError("Platform outage did not fail closed")
        except HTTPError as error:
            if error.code != 503: raise
        cn.execute(target.kubectl("exec","deployment/device-service","--","curl","-fsS","--cacert","/run/secrets/internal-ca.pem","https://127.0.0.1:8082/actuator/health/readiness"))
    finally:
        cn.execute(target.kubectl("scale","deployment/platform-service","--replicas=1"))
        cn.execute(target.kubectl("rollout","status","deployment/platform-service","--timeout=180s"))
    api.call("/devices")
    write_json(folder/"dependency-and-grants.json",{"grants":grants,"platformOutage":"FAIL_CLOSED","deviceLocalReadiness":"UP"})


def health_faults(target, folder):
    """Exercise local dependency failure separately from model/downstream health."""
    verified_endpoints(target)
    def pods():
        rows=json.loads(cn.execute(target.kubectl("get","pods","-l","app.kubernetes.io/part-of=capyiot","-o","json")))["items"]
        return {p["metadata"]["uid"]:sum(c.get("restartCount",0) for c in p.get("status",{}).get("containerStatuses",[])) for p in rows if p.get("status",{}).get("phase") == "Running"}
    def status(service, path):
        if service == "agent-service":
            code=("import ssl,urllib.request,urllib.error;"
                  "c=ssl.create_default_context(cafile='/run/secrets/internal-ca.pem');\n"
                  "try:\n r=urllib.request.urlopen('https://127.0.0.1:8086"+path+"',context=c,timeout=25);print(r.status)\n"
                  "except urllib.error.HTTPError as e:print(e.code)")
            return int(cn.execute(target.kubectl("exec","deployment/agent-service","--","python","-c",code)).strip())
        return int(cn.execute(target.kubectl("exec","deployment/"+service,"--","curl","--silent","--show-error","--max-time","35",
                    "--cacert","/run/secrets/internal-ca.pem","--output","/dev/null","--write-out","%{http_code}","https://127.0.0.1:8082"+path)).strip())
    before=pods();mysql=target.state()["containers"]["mysql"]["containerId"]
    cn.execute(["docker","pause",mysql])
    try:
        live=status("device-service","/actuator/health/liveness")
        ready=status("device-service","/actuator/health/readiness")
        agent_ready=status("agent-service","/health/ready")
        if (live,ready,agent_ready) != (200,503,503): raise AssertionError("Database health did not preserve local liveness/fail readiness")
    finally: cn.execute(["docker","unpause",mysql])
    for service in APPS:
        cn.execute(target.kubectl("rollout","status","deployment/"+service,"--timeout=180s"))
    if pods()!=before: raise AssertionError("Database outage restarted an application")
    cn.execute(target.kubectl("scale","deployment/model-fixture","--replicas=0"))
    try:
        cn.execute(target.kubectl("wait","--for=delete","pod","-l","app=model-fixture","--timeout=60s"))
        if status("agent-service","/health/live")!=200 or status("agent-service","/health/ready")!=200:
            raise AssertionError("Model outage incorrectly affected Agent local health")
        # Metadata diagnostics is intentionally allowed to fail while local probes remain healthy.
        time.sleep(16)
        diagnostic=status("agent-service","/health/diagnostics")
        if diagnostic!=503: raise AssertionError("Model outage was hidden by diagnostics")
    finally:
        cn.execute(target.kubectl("scale","deployment/model-fixture","--replicas=1"))
        cn.execute(target.kubectl("rollout","status","deployment/model-fixture","--timeout=90s"))
    if pods()!=before: raise AssertionError("Model outage restarted an application")
    write_json(folder/"health-faults.json",{"deviceDatabaseFault":{"liveness":live,"readiness":ready},
        "agentDatabaseReadiness":agent_ready,"modelOutage":{"liveness":200,"readiness":200,"diagnostics":diagnostic},"restarts":0})


def migration_failure(target, folder):
    """A failed Job cannot advance a release or mutate application deployments."""
    import base64
    import cloud_native_release as release
    active=json.loads((target.bundle/"active-release.json").read_text())
    candidate=release.read_release(target,active["releaseId"])
    before=json.loads(cn.execute(target.kubectl("get","deployments","-o","json")))
    proof_id="failure-proof-"+uuid.uuid4().hex[:8]
    secret,job=release.migration_job(target,{**candidate,"releaseId":proof_id},"device-service")
    secret["data"]["db-password"]=base64.b64encode(uuid.uuid4().hex.encode()).decode()
    release.apply(target,release.resources([secret,job]))
    try:
        cn.execute(target.kubectl("wait","--for=condition=failed","job/"+job["metadata"]["name"],"--timeout=90s"))
        actual=json.loads(cn.execute(target.kubectl("get","job",job["metadata"]["name"],"-o","json")))
        try: release.deploy(target,{**candidate,"phase":"MIGRATING"}); raise AssertionError("A failed migration advanced deployment")
        except ValueError: pass
        after=json.loads(cn.execute(target.kubectl("get","deployments","-o","json")))
        generations=lambda obj:{r["metadata"]["name"]:r["metadata"]["generation"] for r in obj["items"]}
        if generations(before)!=generations(after): raise AssertionError("Failed migration changed applications")
        write_json(folder/"migration-failure.json",{"jobUid":actual["metadata"]["uid"],"failed":actual["status"].get("failed"),
            "automaticRetry":False,"deploymentBlocked":True,"deploymentGenerations":generations(after)})
    finally:
        cn.execute(target.kubectl("delete","job",job["metadata"]["name"],"--wait=true"))
        cn.execute(target.kubectl("delete","secret",secret["metadata"]["name"]))


def snapshot(target, folder):
    verified_endpoints(target)
    state = target.state()
    # Freeze all application writers, including the external simulator owned by this bundle.
    sim_ids = cn.execute(["docker", "ps", "-q", "--filter", "label=io.capyiot.cn.owner=" + target.manifest["ownerId"], "--filter", "label=io.capyiot.cn.role=simulator"]).decode().split()
    if sim_ids: cn.execute(["docker", "stop", *sim_ids])
    for service in (*APPS, "frontend"):
        cn.execute(target.kubectl("scale", "deployment/" + service, "--replicas=0"))
    cn.execute(target.kubectl("wait", "--for=delete", "pod", "-l", "app.kubernetes.io/part-of=capyiot", "--timeout=120s"))
    mysql, kafka = (state["containers"][s]["containerId"] for s in ("mysql", "kafka"))
    before = inventory(mysql)
    folder.mkdir(mode=0o700)
    sql = cn.execute(["docker", "exec", mysql, "sh", "-c", PASSWORD + "exec mysqldump -uroot --single-transaction --set-gtid-purged=OFF --hex-blob --routines --triggers --events --databases " + " ".join(SCHEMAS)])
    (folder / "five-databases.sql").write_bytes(sql)
    if inventory(mysql) != before: raise AssertionError("Frozen database changed while backing up")
    write_json(folder / "backup.json", {"inventory": before, "offsets": offsets(kafka),
        "sqlSha256": sha(folder / "five-databases.sql"), "files": {"five-databases.sql": sha(folder / "five-databases.sql")}})
    return folder


def kafka_recovery(target, folder):
    verified_endpoints(target)
    kafka = target.state()["containers"]["kafka"]["containerId"]
    before = offsets(kafka)
    start = time.monotonic()
    cn.execute(["docker", "pause", kafka])
    try: time.sleep(20)
    finally: cn.execute(["docker", "unpause", kafka])
    time.sleep(3)
    write_json(folder / "kafka-recovery.json", {"pauseSeconds": time.monotonic() - start,
        "before": before, "after": offsets(kafka), "scope": "Broker recovery only; event-count verification is separate"})
