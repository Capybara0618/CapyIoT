"""Exercise lag-based scale out and recovery in a dedicated CapyIoT demo deployment."""

import argparse
import json
from pathlib import Path
import subprocess
import sys
import time
import uuid
from urllib.error import URLError
from urllib.request import Request, urlopen


def wait(check, description, timeout=100):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            if result := check():
                return result
        except (URLError, TimeoutError):
            pass
        time.sleep(2)
    raise RuntimeError(f"Timed out: {description}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--context", required=True)
    parser.add_argument("--namespace", default="capyiot")
    parser.add_argument("--base-url", default="http://127.0.0.1:18088")
    parser.add_argument("--jaeger-url", default="http://127.0.0.1:16687")
    parser.add_argument("--image", default="capyiot-simulator:m5")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    env = dict(line.split("=", 1) for line in (root / ".env").read_text(encoding="utf-8").splitlines()
               if "=" in line and not line.startswith("#"))
    kube_command = ["kubectl", "--context", args.context, "-n", args.namespace]
    token = None
    suffix = uuid.uuid4().hex[:8]
    secret_name = f"capyiot-smoke-{suffix}"

    def kube(*command, input=None, capture=False):
        return subprocess.run([*kube_command, *command], input=input, capture_output=capture,
                              text=True, timeout=250, check=True).stdout

    def api(path, data=None):
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = f"Bearer {token}"
        with urlopen(Request(args.base_url + "/api/v1" + path, headers=headers,
                            data=json.dumps(data).encode() if data else None), timeout=10) as response:
            return json.load(response)["data"]

    def inspect(kind, name):
        return json.loads(kube("get", kind, name, "-o", "json", capture=True))

    def rollout():
        kube("rollout", "status", "deployment/device-service", "--timeout=180s")

    def simulator(name, rounds):
        name = f"{name}-{suffix}"
        manifest = {
            "apiVersion": "batch/v1", "kind": "Job", "metadata": {"name": name},
            "spec": {"activeDeadlineSeconds": 90, "backoffLimit": 0, "template": {"spec": {
                "automountServiceAccountToken": False, "restartPolicy": "Never",
                "containers": [{
                    "name": "simulator", "image": args.image, "imagePullPolicy": "IfNotPresent",
                    "args": ["--rounds", str(rounds), "--interval-ms", "500"],
                    "env": [{"name": "API_BASE_URL", "value": "http://gateway:8080"},
                            {"name": "MQTT_URL", "value": "tcp://emqx:1883"},
                            {"name": "DEVICE_FILE", "value": "/demo/demo-devices.json"}],
                    "volumeMounts": [{"name": "credentials", "mountPath": "/demo", "readOnly": True}],
                }],
                "volumes": [{"name": "credentials", "secret": {"secretName": secret_name}}],
            }}},
        }
        kube("create", "-f", "-", input=json.dumps(manifest))
        kube("wait", "--for=condition=complete", f"job/{name}", "--timeout=95s")

    def total():
        return wait(lambda: api("/events?size=1&status=PROCESSED"),
                    "device API recovery after replica changes", 20)["total"]

    def ready_replicas():
        return inspect("deployment", "device-service")["status"].get("readyReplicas", 0)

    def notifications_across_pods():
        pods = json.loads(kube("get", "pod", "-l", "app.kubernetes.io/name=device-service",
                               "-o", "json", capture=True))["items"]
        ready = [pod for pod in pods if not pod["metadata"].get("deletionTimestamp") and any(
            condition["type"] == "Ready" and condition["status"] == "True"
            for condition in pod["status"].get("conditions", []))]
        assert len(ready) >= 2
        forwards, streams = [], []
        ports = [18182, 18183]
        try:
            for port, pod in zip(ports, ready[:2]):
                forwards.append(subprocess.Popen(
                    [*kube_command, "port-forward", f"pod/{pod['metadata']['name']}", f"{port}:8082"],
                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                    creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0)))

                def connect():
                    return urlopen(Request(f"http://127.0.0.1:{port}/api/v1/stream",
                                           headers={"Authorization": f"Bearer {token}"}), timeout=10)

                stream = wait(connect, "SSE connection to an individual replica", 20)
                streams.append(stream)
                assert stream.readline().decode().strip() == "event:connected"
            device = api("/devices/" + json.loads(output.read_bytes())[0]["deviceId"])
            update = {"name": f"M5 SSE {suffix}", "enabled": device["enabled"],
                      "buildingId": device["buildingId"], "floorId": device["floorId"],
                      "areaId": device["areaId"], "upstreamNodeId": device["upstreamNodeId"],
                      "expectedVersion": device["version"]}
            with urlopen(Request(f"http://127.0.0.1:{ports[0]}/api/v1/devices/{device['id']}",
                                 method="PATCH", data=json.dumps(update).encode(), headers={
                                     "Content-Type": "application/json", "Authorization": f"Bearer {token}"}),
                         timeout=10) as response:
                assert response.status == 200
            for stream in streams:
                deadline = time.monotonic() + 15
                while time.monotonic() < deadline:
                    line = stream.readline().decode().strip()
                    if not line:
                        continue
                    if line.startswith("data:") and json.loads(line[5:]).get("deviceId") == device["id"]:
                        break
                else:
                    raise RuntimeError("A replica missed the committed device notification")
            print("SSE delivered the same committed asset change to two different replicas.", flush=True)
        finally:
            for stream in streams:
                stream.close()
            for process in forwards:
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)

    scaled = inspect("scaledobject", "device-service")
    trigger = scaled["spec"]["triggers"][0]["metadata"]
    assert int(trigger["lagThreshold"]) <= 12, "Use the smoke-test threshold (10) in this demo"
    assert scaled["spec"]["minReplicaCount"] == 1 and scaled["spec"]["maxReplicaCount"] >= 2
    token = wait(lambda: api("/auth/login", {"username": "admin", "password": env["DEMO_ADMIN_PASSWORD"]}),
                 "gateway login")["accessToken"]
    output = root / "var" / "k8s-smoke-devices.json"
    subprocess.run([sys.executable, str(root / "scripts" / "demo_devices.py"), "--base-url", args.base_url,
                    "--prefix", "K8S", "--output", str(output)], timeout=100, check=True)
    kube("create", "secret", "generic", secret_name, f"--from-file=demo-devices.json={output}")
    assert len(json.loads(output.read_bytes())) == 12

    topic = kube("exec", "deployment/kafka", "--", "/opt/kafka/bin/kafka-topics.sh", "--bootstrap-server",
                 "kafka:29092", "--describe", "--topic", "iot.device.events.v1", capture=True)
    assert "PartitionCount: 8" in topic
    wait(lambda: ready_replicas() == 1 and inspect("deployment", "device-service")["spec"]["replicas"] == 1,
         "initial single consumer replica", 150)
    baseline = total()
    simulator("m5-initial", 1)
    wait(lambda: total() >= baseline + 12, "initial Kafka commits")
    baseline = total()

    kube("set", "env", "deployment/device-service", "SPRING_KAFKA_LISTENER_AUTO_STARTUP=false")
    try:
        rollout()
        simulator("m5-backlog", 6)
        assert total() == baseline, "Device consumer must be paused while creating the test backlog"
        wait(lambda: ready_replicas() > 1, "KEDA scale out with Kafka backlog", 150)
        expanded = inspect("deployment", "device-service")
        count = expanded["status"]["readyReplicas"]
        assert 1 < count <= scaled["spec"]["maxReplicaCount"]
        observed = api("/observability/deployment")
        assert observed["k8sEnabled"] and observed["reachable"] and observed["scaling"]["ready"]
        assert any(row["service"] == "device-service" and row["desired"] > 1 for row in observed["workloads"])
        print(f"KEDA scaled device-service from 1 to {count} ready replicas with pending Kafka events.", flush=True)
        notifications_across_pods()
    finally:
        kube("set", "env", "deployment/device-service", "SPRING_KAFKA_LISTENER_AUTO_STARTUP-")
    rollout()
    wait(lambda: total() >= baseline + 72, "consume queued MQTT events")
    wait(lambda: ready_replicas() == 1 and inspect("deployment", "device-service")["spec"]["replicas"] == 1,
         "KEDA return to one replica", 150)
    baseline = total()
    pods = json.loads(kube("get", "pod", "-l", "app.kubernetes.io/name=device-service", "-o", "json", capture=True))
    ready = next(item for item in pods["items"] if not item["metadata"].get("deletionTimestamp")
                 and any(condition["type"] == "Ready" and condition["status"] == "True"
                         for condition in item["status"].get("conditions", [])))
    kube("delete", "pod", ready["metadata"]["name"], "--wait=false")
    simulator("m5-replacement", 2)
    rollout()
    wait(lambda: total() >= baseline + 24, "process MQTT events after pod replacement")
    wait(lambda: any(row["metadata"]["uid"] != ready["metadata"]["uid"]
                     for row in json.loads(kube("get", "pod", "-l", "app.kubernetes.io/name=device-service",
                                                "-o", "json", capture=True))["items"]),
         "replacement pod UID")

    def scraped():
        snapshot = api("/observability/metrics?minutes=15&service=device-service")
        if not snapshot["prometheusAvailable"]:
            return None
        samples = {series["id"]: series["points"] for series in snapshot["series"]}
        return any(point["value"] is not None for point in samples.get("heapMiB", [])) and any(
            point["value"] is not None and point["value"] > 0 for point in samples.get("lag", []))

    wait(scraped, "real JVM samples and positive Kafka lag in Prometheus")
    trace = api("/events?size=1&status=PROCESSED")["items"][0]["traceId"]
    assert len(trace) == 32, "MQTT event must carry its OTel trace ID"

    def traced():
        with urlopen(f"{args.jaeger_url}/api/v3/traces/{trace}", timeout=5) as response:
            batches = [json.loads(line) for line in response if line.strip()]
        names = {attribute["value"]["stringValue"]
                 for batch in batches for resource in batch.get("result", {}).get("resourceSpans", [])
                 for attribute in resource["resource"]["attributes"] if attribute["key"] == "service.name"}
        return {"device-ingestion", "device-service"} <= names

    wait(traced, "MQTT and Kafka spans in the same Jaeger trace")
    print("PASS: eight partitions, KEDA scale out/down, pod replacement, MQTT processing, Prometheus and Jaeger")


if __name__ == "__main__":
    main()
