"""Bounded MS-09 measurements against an explicitly selected isolated deployment.

Run after functional acceptance. Requires the same explicit smoke_context variables;
never discovers credentials or connects to the experience environment by default.
Results are observations, not an undeclared capacity SLA.
"""

import argparse
import json
import math
import os
import platform
import re
import socket
import struct
import subprocess
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, datetime
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from microservices_common import Target, run, sha, write_json
from microservices_control import offsets
from smoke_context import api_base, credentials, tls_context
from video_protocol_smoke import encoded, packet, receive


def distribution(values):
    ordered = sorted(values)
    return {
        "samples": len(ordered),
        **{
            name: round(ordered[max(0, math.ceil(len(ordered) * q) - 1)], 3)
            for name, q in (("p50Ms", 0.5), ("p95Ms", 0.95), ("p99Ms", 0.99))
        },
        "maxMs": round(ordered[-1], 3),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--devices", required=True, type=Path)
    parser.add_argument(
        "--confirmation-only",
        action="store_true",
        help="Retest only the receipt measurement; preserve earlier API/ingress results separately",
    )
    parser.add_argument("--policy", required=True, type=Path)
    parser.add_argument("--report-dir", required=True, type=Path)
    args = parser.parse_args()
    target = Target(**json.loads(os.environ["CAPYIOT_MS_TARGET"]), verification=True)
    target.preflight()
    if target.manifest["modelMode"] != "offline":
        raise ValueError(
            "This baseline uses the offline model fixture; real model verification is separate"
        )
    if args.report_dir.exists():
        raise ValueError("Use a new private report directory")
    args.report_dir.mkdir(parents=True)
    report = {
        "status": "RUNNING",
        "project": target.project,
        "startedAt": datetime.now(UTC).isoformat(),
        "policySha256": sha(args.policy),
        "interpretation": "Local baseline; no production capacity SLA was agreed",
        "selectedMeasurements": ["confirmation"]
        if args.confirmation_only
        else ["api", "ingress", "confirmation"],
        "apiConcurrency": 4,
        "apiWarmupPerPath": 10,
        "apiSamplesPerPath": 120,
        "resourceWindowSeconds": 60,
        "pollIntervalSeconds": 0.1,
        "sourceCommit": run(["git", "rev-parse", "HEAD"]).decode().strip(),
        "hostPlatform": platform.platform(),
        "hostLogicalCpuCount": os.cpu_count(),
        "activeMeasuredPublishers": 1,
        "arrivalTiming": "Host monotonic publish-to-successful-API-poll upper bound, including client/network/poll overhead",
    }
    devices = json.loads(args.devices.read_text(encoding="utf-8"))
    report["simulatedDevicesInInputFile"] = len(devices)
    device = next(d for d in devices if d["deviceType"] == "IPC")
    tls = tls_context()
    token = None
    connection = None
    unpause = False
    resources = []
    collector = None

    def call(path, body=None):
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        if body is not None:
            headers["Idempotency-Key"] = uuid.uuid4().hex
        try:
            response = urlopen(
                Request(
                    api_base() + path,
                    headers=headers,
                    data=None if body is None else json.dumps(body).encode(),
                ),
                timeout=15,
                context=tls,
            )
        except HTTPError as error:
            response = error
        with response:
            if response.status not in (200, 201, 202):
                raise RuntimeError(
                    f"Measurement request {path.split('?')[0]} failed: HTTP {response.status}"
                )
            return json.load(response)["data"]

    def wait(check, seconds=40):
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            if check():
                return
            time.sleep(0.1)
        raise AssertionError("Measurement observation timed out")

    sequence = 0
    session = uuid.uuid4().hex

    def heartbeat(event_type="HEARTBEAT", related=None):
        nonlocal sequence
        sequence += 1
        event = {
            "eventId": str(uuid.uuid4()),
            "schemaVersion": 1,
            "deviceId": device["deviceId"],
            "sessionId": session,
            "eventType": event_type,
            "streamKey": "HEARTBEAT"
            if event_type == "HEARTBEAT"
            else "EVENT/DEVICE/device",
            "sequence": sequence,
            "occurredAt": datetime.now(UTC).isoformat().replace("+00:00", "Z"),
            "payload": {"bootId": session}
            if event_type == "HEARTBEAT"
            else {
                "resourceType": "DEVICE",
                "resourceKey": "device",
                "faultCode": "CAMERA_DEVICE_ERROR",
                **(
                    {
                        "relatedFaultEventId": related,
                        "recoveryEvidence": "bounded virtual-device recovery",
                    }
                    if related
                    else {}
                ),
            },
        }
        connection.sendall(
            packet(
                0x32,
                encoded(topic)
                + struct.pack("!H", sequence)
                + json.dumps(event).encode(),
            )
        )
        kind, ack = receive(connection)
        if kind != 0x40 or ack != struct.pack("!H", sequence):
            raise AssertionError("Publisher PUBACK missing")
        return event["eventId"]

    def applied(event_id):
        # This is test-only evidence, never a cross-schema production dependency.
        result = target.sql(
            "SELECT COUNT(*) FROM device_db.device_event_receipt WHERE event_id='"
            + event_id
            + "' AND processing_status='APPLIED'"
        )
        return result.strip().splitlines()[-1] == "1"

    def subscriber_acks():
        metrics = run(
            target.compose(
                "exec",
                "-T",
                "device-ingestion",
                "curl",
                "--fail",
                "--silent",
                "--cacert",
                "/run/secrets/internal-ca.pem",
                "https://device-ingestion:8081/actuator/prometheus",
            )
        ).decode()
        match = re.search(
            r"^capyiot_mqtt_ack_total(?:\{[^\n]*\})? ([0-9.eE+]+)$",
            metrics,
            re.MULTILINE,
        )
        if not match:
            raise AssertionError("Subscriber acknowledgement counter unavailable")
        return float(match.group(1))

    def collect():
        end = time.monotonic() + 60
        while time.monotonic() < end:
            result = subprocess.run(
                [
                    "docker",
                    "stats",
                    "--no-stream",
                    "--format",
                    "{{json .}}",
                    *container_names,
                ],
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                check=True,
            )
            resources.append(
                {
                    "at": datetime.now(UTC).isoformat(),
                    "rows": [
                        json.loads(line) for line in result.stdout.decode().splitlines()
                    ],
                }
            )
            time.sleep(2)

    try:
        token = call(
            "/auth/login",
            {
                "loginName": "supervisor",
                "password": credentials()["DEMO_ADMIN_PASSWORD"],
            },
        )["accessToken"]
        report["registeredDeviceCount"] = call("/devices?size=100")["total"]
        report["docker"] = json.loads(
            run(["docker", "info", "--format", "{{json .}} "])
        )
        # Retain hardware totals only, never the complete daemon configuration.
        report["docker"] = {
            key: report["docker"].get(key)
            for key in (
                "NCPU",
                "MemTotal",
                "ServerVersion",
                "KernelVersion",
                "OperatingSystem",
            )
        }
        container_names = (
            run(
                [
                    "docker",
                    "ps",
                    "--filter",
                    "label=com.docker.compose.project=" + target.project,
                    "--format",
                    "{{.Names}}",
                ]
            )
            .decode()
            .split()
        )
        inspected = json.loads(run(["docker", "inspect", *container_names]))
        report["containers"] = [
            {
                "name": item["Name"],
                "imageId": item["Image"],
                "memoryLimitBytes": item["HostConfig"]["Memory"],
                "nanoCpus": item["HostConfig"]["NanoCpus"],
            }
            for item in inspected
        ]
        report["otherRunningContainers"] = [
            name
            for name in run(["docker", "ps", "--format", "{{.Names}}"])
            .decode()
            .splitlines()
            if name not in container_names
        ]
        collector = threading.Thread(target=collect, daemon=True)
        collector.start()
        if not args.confirmation_only:
            api = {}
            for path in (
                "/devices",
                "/incidents",
                "/workorders",
                "/topology/context?deviceId=" + device["deviceId"],
            ):
                print("Measuring bounded API sample: " + path.split("?")[0], flush=True)
                for _ in range(10):
                    call(path)

                def sample(_, selected=path):
                    started = time.perf_counter()
                    try:
                        call(selected)
                        return (time.perf_counter() - started) * 1000, True
                    except (RuntimeError, OSError):
                        return (time.perf_counter() - started) * 1000, False

                with ThreadPoolExecutor(max_workers=4) as pool:
                    samples = list(pool.map(sample, range(120)))
                api[path] = {
                    **distribution([elapsed for elapsed, _ in samples]),
                    "rawMs": [elapsed for elapsed, _ in samples],
                    "errors": sum(not ok for _, ok in samples),
                    "errorRate": sum(not ok for _, ok in samples) / len(samples),
                }
            report["api"] = api
            write_json(args.report_dir / "measurements.json", report)
            if any(item["errors"] for item in api.values()):
                raise AssertionError("Unexpected API errors during baseline")
            identity = call(
                "/device-sessions",
                {
                    "deviceId": device["deviceId"],
                    "secret": device["deviceSecret"],
                    "sessionId": session,
                },
            )
            raw = socket.create_connection(
                ("localhost", int(target.values["MS_MQTT_PORT"])), timeout=10
            )
            connection = tls.wrap_socket(raw, server_hostname="localhost")
            connection.settimeout(10)
            body = (
                encoded("MQTT")
                + bytes([4, 194])
                + struct.pack("!H", 30)
                + encoded(device["deviceId"])
                + encoded(device["deviceId"])
                + encoded(identity["sessionToken"])
            )
            connection.sendall(packet(0x10, body))
            assert receive(connection) == (0x20, b"\x00\x00")
            topic = f"capyiot/v1/devices/{device['deviceId']}/sessions/{session}/events"
            arrivals = []
            arrival_window = time.monotonic()
            for _ in range(30):
                before = call("/devices/" + device["deviceId"])[
                    "lastHeartbeatReceivedAt"
                ]
                start = time.perf_counter()
                heartbeat()
                wait(
                    lambda original=before: (
                        call("/devices/" + device["deviceId"])[
                            "lastHeartbeatReceivedAt"
                        ]
                        != original
                    )
                )
                arrivals.append((time.perf_counter() - start) * 1000)
            report["publishToVisibleHeartbeat"] = distribution(arrivals)
            report["heartbeatArrivalRawMs"] = arrivals
            print("Completed 30 publish-to-state observations", flush=True)
            report["heartbeatArrivalInputWindowSeconds"] = (
                time.monotonic() - arrival_window
            )
            report["heartbeatArrivalInputRatePerSecond"] = (
                30 / report["heartbeatArrivalInputWindowSeconds"]
            )
            anomalies = []
            for _ in range(5):
                path = "/anomalies?deviceId=" + device["deviceId"]
                old_ids = {a["anomalyId"] for a in call(path)["items"]}
                start = time.perf_counter()
                fault_event = heartbeat("DEVICE_FAULT")
                wait(
                    lambda previous=old_ids, selected=path: any(
                        a["anomalyId"] not in previous and a["status"] == "ACTIVE"
                        for a in call(selected)["items"]
                    )
                )
                anomalies.append((time.perf_counter() - start) * 1000)
                fresh = next(
                    a["anomalyId"]
                    for a in call(path)["items"]
                    if a["anomalyId"] not in old_ids
                )
                heartbeat("DEVICE_RECOVERED", fault_event)
                wait(
                    lambda anomaly_id=fresh: (
                        call("/anomalies/" + anomaly_id)["status"] == "RECOVERED"
                    )
                )
            report["publishToVisibleAnomaly"] = distribution(anomalies)
            report["anomalyArrivalRawMs"] = anomalies
            print("Completed 5 fault/recovery observations", flush=True)
            # Isolated Kafka pause: publisher PUBACK alone must not imply a database fact.
            kafka = target.project + "-kafka-1"
            report["consumerOffsetsBeforeOutage"] = offsets(kafka)
            before_acks = subscriber_acks()
            run(["docker", "pause", kafka])
            unpause = True
            backlog = []
            started = time.monotonic()
            for _ in range(20):
                backlog.append(heartbeat())
                time.sleep(0.1)
            wait_until = started + 15
            while time.monotonic() < wait_until:
                time.sleep(min(0.5, wait_until - time.monotonic()))
            assert not any(applied(event_id) for event_id in backlog)
            paused_acks = subscriber_acks()
            assert paused_acks == before_acks, (
                "Subscriber ACK advanced without a durable Kafka acknowledgement"
            )
            resumed = time.perf_counter()
            run(["docker", "unpause", kafka])
            unpause = False
            wait(lambda: all(applied(event_id) for event_id in backlog), seconds=90)
            wait(lambda: subscriber_acks() >= before_acks + len(backlog), seconds=30)
            report["kafkaRecovery"] = {
                "pausedSeconds": round(
                    time.monotonic() - started - (time.perf_counter() - resumed), 3
                ),
                "queuedHeartbeats": 20,
                "recoveryMs": round((time.perf_counter() - resumed) * 1000, 3),
                "appliedExactlyOnce": True,
                "subscriberAckBefore": before_acks,
                "subscriberAckWhilePaused": paused_acks,
                "subscriberAckAfter": subscriber_acks(),
            }
            report["consumerOffsetsAfterRecovery"] = offsets(kafka)
            print("Completed bounded Kafka pause and recovery", flush=True)
        # The model is the offline fixture; confirmation and the delegated Java
        # command/receipt still traverse the real Agent and workflow services.
        receipts = []
        for _ in range(5):
            conversation = call("/agent/conversations", {"title": "有界确认回执采样"})
            message_path = (
                "/agent/conversations/" + conversation["conversationId"] + "/messages"
            )
            with urlopen(
                Request(
                    api_base() + message_path,
                    headers={
                        "Content-Type": "application/json",
                        "Authorization": "Bearer " + token,
                        "Accept": "text/event-stream",
                        "Idempotency-Key": uuid.uuid4().hex,
                    },
                    data=json.dumps(
                        {
                            "content": "draft-fixture MANUAL 请为可见的模拟设备生成维修草稿，等待确认。"
                        }
                    ).encode(),
                ),
                context=tls,
                timeout=90,
            ) as response:
                wire = response.read().decode()
            (
                args.report_dir / ("receipt-model-" + str(len(receipts) + 1) + ".sse")
            ).write_text(wire, encoding="utf-8")
            ready = next(
                (
                    json.loads(line[6:])
                    for part in wire.split("\n\n")
                    if "event: draft.ready" in part
                    for line in part.splitlines()
                    if line.startswith("data: ")
                ),
                None,
            )
            if ready is None:
                raise AssertionError(
                    "Receipt measurement did not obtain a draft; inspect the private SSE record"
                )
            draft = call("/agent/drafts/" + ready["draftId"])
            started = time.perf_counter()
            call(
                "/agent/drafts/" + draft["draftId"] + "/confirm",
                {
                    "draftRevision": draft["revision"],
                    "contentHash": draft["contentHash"],
                },
            )
            wait(
                lambda draft_id=draft["draftId"]: (
                    call("/agent/drafts/" + draft_id)["execution"]["status"]
                    == "SUCCEEDED"
                ),
                seconds=45,
            )
            receipts.append((time.perf_counter() - started) * 1000)
            print(
                "Completed confirmed receipt sample " + str(len(receipts)), flush=True
            )
        report["confirmationToReceipt"] = distribution(receipts)
        report["confirmationRawMs"] = receipts
        report["status"] = "PASSED_BASELINE_FUNCTIONAL_GATES"
    finally:
        if unpause:
            run(["docker", "unpause", target.project + "-kafka-1"])
        if connection:
            connection.close()
        if token:
            call("/auth/logout", {})
        if collector:
            collector.join(timeout=65)
        report["resources"] = resources
        report["finishedAt"] = datetime.now(UTC).isoformat()
        if report["status"] == "RUNNING":
            report["status"] = "FAILED"
        write_json(args.report_dir / "measurements.json", report)
    print(
        "Bounded baseline completed; private report: "
        + str(args.report_dir / "measurements.json")
    )


if __name__ == "__main__":
    main()
