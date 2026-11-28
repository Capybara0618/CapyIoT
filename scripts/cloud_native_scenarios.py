"""Real scoped Kubernetes failures, API sampling and frozen SQL recovery evidence."""

import json
import math
import ssl
import statistics
import time
import uuid
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
        self.call("/auth/login", {"loginName": "supervisor", "password": (target.bundle / "secrets/demo-admin-password").read_text()}, login=True)

    def call(self, path, body=None, *, login=False, key=None):
        headers = {"Content-Type": "application/json", "X-Correlation-Id": uuid.uuid4().hex}
        if self.token: headers["Authorization"] = "Bearer " + self.token
        if body is not None: headers["Idempotency-Key"] = key or uuid.uuid4().hex
        request = Request(self.base + path, data=None if body is None else json.dumps(body).encode(), headers=headers)
        with urlopen(request, context=self.tls, timeout=15) as response:
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


def resilience(target, folder):
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
        api.call("/devices")
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
            for _ in range(20): api.call("/devices")
        finally:
            cn.execute(target.kubectl("scale", "deployment/" + service, "--replicas=1"))
            cn.execute(target.kubectl("rollout", "status", "deployment/" + service, "--timeout=180s"))
    write_json(folder / "replica-recovery.json", {"services": ["gateway", "platform-service"], "replicas": [1, 2, 1]})


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
