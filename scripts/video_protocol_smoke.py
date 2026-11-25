"""Run real TLS/MQTT authorization checks against an isolated local verification stack.

Not executed during module development. Device secrets and CONNECT tokens are never printed.
Publisher PUBACK proves broker acceptance only; downstream assertions query the Core API.
"""

import argparse
import json
import os
import socket
import ssl
import struct
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]


def encoded(value):
    data = value.encode()
    return struct.pack("!H", len(data)) + data


def packet(kind, body):
    length, encoded_length = len(body), bytearray()
    while True:
        byte = length % 128
        length //= 128
        encoded_length.append(byte | (128 if length else 0))
        if not length:
            return bytes([kind]) + encoded_length + body


def exact(connection, size):
    result = bytearray()
    while len(result) < size:
        chunk = connection.recv(size - len(result))
        if not chunk:
            raise ConnectionError("Broker disconnected")
        result.extend(chunk)
    return bytes(result)


def receive(connection):
    kind = exact(connection, 1)[0]
    length, multiplier = 0, 1
    for _ in range(4):
        byte = exact(connection, 1)[0]
        length += (byte & 127) * multiplier
        if not byte & 128:
            if length > 65536:
                raise RuntimeError("Unexpectedly large broker response")
            return kind, exact(connection, length)
        multiplier *= 128
    raise RuntimeError("Invalid MQTT packet length")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="https://localhost:8443")
    parser.add_argument("--host", default="localhost")
    parser.add_argument("--port", type=int, default=8883)
    parser.add_argument("--ca-file", type=Path, default=ROOT / "var/tls/ca.pem")
    parser.add_argument("--devices", type=Path, default=ROOT / "var/video-devices.json")
    args = parser.parse_args()
    tls = ssl.create_default_context(cafile=args.ca_file)
    values = dict(line.split("=", 1) for line in (ROOT / ".env").read_text().splitlines() if "=" in line and not line.startswith("#"))
    devices = json.loads(args.devices.read_text())
    device = next(d for d in devices if d["deviceType"] == "IPC")
    other = next(d for d in devices if d["deviceId"] != device["deviceId"])
    user_token = None

    def api(path, body=None, expected=200):
        headers = {"Content-Type": "application/json"}
        if user_token:
            headers["Authorization"] = "Bearer " + user_token
        if body is not None:
            headers["Idempotency-Key"] = str(uuid.uuid4())
        request = Request(args.base_url.rstrip("/") + "/api/v1" + path, headers=headers, data=None if body is None else json.dumps(body).encode())
        try:
            response = urlopen(request, context=tls, timeout=15)
        except HTTPError as error:
            response = error
        with response:
            expected_codes = expected if isinstance(expected, tuple) else (expected,)
            if response.status not in expected_codes:
                raise AssertionError(f"HTTP {response.status}, expected {expected}: {path}")
            return json.load(response).get("data")

    def connect(username, client_id, token, expected=True):
        raw = socket.create_connection((args.host, args.port), timeout=5)
        connection = tls.wrap_socket(raw, server_hostname=args.host)
        connection.settimeout(3)
        flags = 2 | (128 if username is not None else 0) | (64 if token is not None else 0)
        body = encoded("MQTT") + bytes([4, flags]) + struct.pack("!H", 20) + encoded(client_id)
        if username is not None:
            body += encoded(username)
        if token is not None:
            body += encoded(token)
        connection.sendall(packet(0x10, body))
        try:
            kind, ack = receive(connection)
            accepted = kind == 0x20 and len(ack) == 2 and ack[1] == 0
        except (ConnectionError, socket.timeout):
            accepted = False
        if accepted != expected:
            connection.close()
            raise AssertionError("Unexpected MQTT CONNECT authorization result")
        if not accepted:
            connection.close()
            return None
        return connection

    def event(session, sequence):
        return json.dumps({"eventId": str(uuid.uuid4()), "schemaVersion": 1, "deviceId": device["deviceId"], "sessionId": session,
                           "eventType": "HEARTBEAT", "streamKey": "HEARTBEAT", "sequence": sequence,
                           "occurredAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"), "payload": {"bootId": session}}).encode()

    def publish(connection, topic, body, allowed=True, retained=False):
        connection.sendall(packet(0x33 if retained else 0x32, encoded(topic) + struct.pack("!H", 1) + body))
        try:
            kind, ack = receive(connection)
            accepted = kind == 0x40 and ack == struct.pack("!H", 1)
        except (ConnectionError, socket.timeout):
            accepted = False
        if accepted != allowed:
            raise AssertionError("Unexpected MQTT publish authorization result")

    user_token = api("/auth/login", {"loginName": "supervisor", "password": values["DEMO_ADMIN_PASSWORD"]})["accessToken"]
    try:
        session = str(uuid.uuid4())
        result = api("/device-sessions", {"deviceId": device["deviceId"], "secret": device["deviceSecret"], "sessionId": session})
        topic = f"capyiot/v1/devices/{device['deviceId']}/sessions/{session}/events"
        connect(None, "anonymous-" + uuid.uuid4().hex, None, expected=False)
        connect(other["deviceId"], device["deviceId"], result["sessionToken"], expected=False)
        connect(device["deviceId"], other["deviceId"], result["sessionToken"], expected=False)
        connect(device["deviceId"], device["deviceId"], "invalid-device-token", expected=False)
        untrusted = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        try:
            with socket.create_connection((args.host, args.port), timeout=5) as raw:
                with untrusted.wrap_socket(raw, server_hostname=args.host):
                    raise AssertionError("Untrusted CA was accepted")
        except ssl.SSLCertVerificationError:
            pass
        with connect(device["deviceId"], device["deviceId"], result["sessionToken"]) as connection:
            publish(connection, topic.replace(device["deviceId"], other["deviceId"]), event(session, 1), allowed=False)
        with connect(device["deviceId"], device["deviceId"], result["sessionToken"]) as connection:
            publish(connection, topic.replace(session, "other-session"), event(session, 1), allowed=False)
        with connect(device["deviceId"], device["deviceId"], result["sessionToken"]) as connection:
            publish(connection, "capyiot/CAMPUS-A/devices/" + device["deviceId"] + "/events", event(session, 1), allowed=False)
        with connect(device["deviceId"], device["deviceId"], result["sessionToken"]) as connection:
            connection.sendall(packet(0x82, struct.pack("!H", 1) + encoded("#") + bytes([1])))
            try:
                kind, reply = receive(connection)
                if kind != 0x90 or reply[-1] != 0x80:
                    raise AssertionError("A device was allowed to subscribe to other devices")
            except (ConnectionError, socket.timeout):
                pass
        with connect(device["deviceId"], device["deviceId"], result["sessionToken"]) as connection:
            connection.sendall(packet(0x30, encoded(topic) + event(session, 1)))
            connection.sendall(b"\xc0\x00")
            try:
                receive(connection)
                raise AssertionError("QoS 0 publish was not disconnected by the QoS 1-only ACL")
            except (ConnectionError, socket.timeout):
                pass
        with connect(device["deviceId"], device["deviceId"], result["sessionToken"]) as connection:
            publish(connection, topic, event(session, 1), allowed=False, retained=True)
        before_valid = api("/devices/" + device["deviceId"])["lastHeartbeatReceivedAt"]
        with connect(device["deviceId"], device["deviceId"], result["sessionToken"]) as connection:
            publish(connection, topic, event(session, 1))
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            current = api("/devices/" + device["deviceId"])
            if current["connectivityStatus"] == "ONLINE" and current["lastHeartbeatReceivedAt"] and current["lastHeartbeatReceivedAt"] != before_valid:
                break
            time.sleep(0.5)
        else:
            raise AssertionError("Accepted heartbeat did not reach the Core state")
        api("/device-sessions", {"deviceId": device["deviceId"], "secret": device["deviceSecret"], "sessionId": str(uuid.uuid4())})
        before = api("/devices/" + device["deviceId"])["lastHeartbeatReceivedAt"]
        # A stateless JWT may reconnect until expiry, but the revoked session cannot update state.
        # The assertion concerns downstream authorization, not an unsupported instant JWT revocation claim.
        with connect(device["deviceId"], device["deviceId"], result["sessionToken"]) as connection:
            publish(connection, topic, event(session, 2))
        time.sleep(3)
        after = api("/devices/" + device["deviceId"])["lastHeartbeatReceivedAt"]
        if before != after:
            raise AssertionError("An old session changed current device state")
        # Rotation must kick the existing broker connection and invalidate the old secret.
        with connect(device["deviceId"], device["deviceId"], result["sessionToken"]) as connection:
            old_secret = device["deviceSecret"]
            version = api("/devices/" + device["deviceId"])["version"]
            rotated = api("/devices/" + device["deviceId"] + "/credential/rotate", {"expectedVersion": version, "reason": "统一验收：核查凭据轮换和连接撤销"})
            device["deviceSecret"] = rotated["deviceSecret"]
            temporary = args.devices.with_suffix(".tmp")
            temporary.write_text(json.dumps(devices, ensure_ascii=False, indent=2), encoding="utf-8")
            os.chmod(temporary, 0o600); temporary.replace(args.devices)
            connection.settimeout(10)
            try:
                receive(connection)
                raise AssertionError("Broker connection remained active after credential rotation")
            except ConnectionError:
                pass
            api("/device-sessions", {"deviceId": device["deviceId"], "secret": old_secret, "sessionId": str(uuid.uuid4())}, expected=401)
        new_session = str(uuid.uuid4())
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            renewed = api("/device-sessions", {"deviceId": device["deviceId"], "secret": device["deviceSecret"], "sessionId": new_session}, expected=(200, 503))
            if renewed:
                break
            time.sleep(0.5)
        else:
            raise AssertionError("New credentials could not reconnect after durable disconnect processing")
        with connect(device["deviceId"], device["deviceId"], renewed["sessionToken"]):
            pass
        print("Real TLS/MQTT checks passed: trusted CA, authentication binding, topic ACL, retained denial, accepted input and stale-session rejection.")
    finally:
        api("/auth/logout", {})


if __name__ == "__main__":
    main()
