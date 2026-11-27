"""Provision virtual video-surveillance assets through the same public APIs as operators.

Credentials stay under ignored var/. This is a provisioner, not a test or a device collector.
"""

import argparse
import json
import os
import re
import ssl
import uuid
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import urlencode, urlparse
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="https://localhost:8443")
    parser.add_argument("--ca-file", type=Path, default=ROOT / "var/tls/ca.pem")
    parser.add_argument("--prefix", default="VIDEO")
    parser.add_argument("--output", type=Path, default=ROOT / "var/video-devices.json")
    parser.add_argument("--env-file", type=Path, default=ROOT / ".env")
    args = parser.parse_args()
    if urlparse(args.base_url).scheme != "https" or not re.fullmatch(
        r"[A-Za-z0-9_-]{1,32}", args.prefix
    ):
        raise SystemExit("Use an HTTPS URL and a 1–32 character asset prefix.")
    tls = ssl.create_default_context(cafile=args.ca_file)
    values = dict(
        line.split("=", 1)
        for line in args.env_file.read_text(encoding="utf-8-sig").splitlines()
        if "=" in line and not line.lstrip().startswith("#")
    )
    token = None
    saved = (
        json.loads(args.output.read_text(encoding="utf-8"))
        if args.output.exists()
        else []
    )
    credentials = {d["assetCode"]: d for d in saved}

    def persist():
        args.output.parent.mkdir(parents=True, exist_ok=True)
        temporary = args.output.with_suffix(".tmp")
        # The secret is returned once. Save it before commissioning or creating relations.
        with open(
            temporary,
            "w",
            encoding="utf-8",
            opener=lambda path, flags: os.open(path, flags, 0o600),
        ) as output:
            json.dump(list(credentials.values()), output, ensure_ascii=False, indent=2)
        os.chmod(temporary, 0o600)
        temporary.replace(args.output)

    def call(path, body=None, method=None, command=None):
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        if command:
            headers["Idempotency-Key"] = "video-" + str(
                uuid.uuid5(uuid.NAMESPACE_URL, args.prefix + "/" + command)
            )
        request = Request(
            args.base_url.rstrip("/") + "/api/v1" + path,
            headers=headers,
            data=None if body is None else json.dumps(body).encode(),
            method=method,
        )
        try:
            with urlopen(request, timeout=20, context=tls) as response:
                return json.load(response)["data"]
        except HTTPError as error:
            # Never echo response bodies containing a one-time secret or a login token.
            raise RuntimeError(
                f"Provisioning request {method or ('POST' if body else 'GET')} {path.split('?')[0]} failed: HTTP {error.code}"
            ) from None

    token = call(
        "/auth/login",
        {"loginName": "supervisor", "password": values["DEMO_ADMIN_PASSWORD"]},
    )["accessToken"]
    try:
        areas = call("/areas?parkId=CAMPUS-A")
        parent = None
        for kind, suffix, name in [
            ("BUILDING", "BUILDING", "视频演示楼"),
            ("FLOOR", "FLOOR", "首层"),
            ("AREA", "AREA", "入口及走廊"),
        ]:
            code = args.prefix + "-" + suffix
            existing = next((a for a in areas if a["areaCode"] == code), None)
            if existing and existing.get("parentAreaId") != parent:
                raise RuntimeError(
                    "Existing area hierarchy differs; choose a new prefix or correct it in the platform."
                )
            result = existing or call(
                "/areas",
                {
                    "parentAreaId": parent,
                    "areaType": kind,
                    "areaCode": code,
                    "name": name,
                },
                command="area/" + suffix,
            )
            parent = result["areaId"]
        registered = {
            d["assetCode"]: d
            for d in call(
                "/devices?" + urlencode({"keyword": args.prefix, "size": 100})
            )["items"]
        }
        devices = []
        specs = [
            ("SW", "SWITCH", "入口接入交换机", {"poeOutput": True}),
            ("IPC1", "IPC", "入口摄像机", {"poeInput": True}),
            ("IPC2", "IPC", "走廊摄像机", {"poeInput": True}),
            ("NVR", "NVR", "录像存储主机", {}),
        ]
        for suffix, kind, name, capabilities in specs:
            code = args.prefix + "-" + suffix
            existing = registered.get(code)
            if existing:
                config = credentials.get(code)
                if not config or config["deviceId"] != existing["deviceId"]:
                    raise RuntimeError(
                        f"Local one-time credential is missing for {code}; explicitly rotate it in the platform before retrying."
                    )
                if existing["deviceType"] != kind or existing["areaId"] != parent:
                    raise RuntimeError(
                        "Existing device differs from the requested topology."
                    )
            else:
                result = call(
                    "/devices",
                    {
                        "assetCode": code,
                        "deviceType": kind,
                        "name": name,
                        "areaId": parent,
                        "capabilities": capabilities,
                        "criticality": "HIGH" if kind == "SWITCH" else "NORMAL",
                    },
                    command="device/" + suffix,
                )
                existing = result["device"]
                if not result.get("deviceSecret"):
                    raise RuntimeError(
                        "One-time credential was already issued; recover the local credential file or rotate explicitly."
                    )
                credentials[code] = {
                    "assetCode": code,
                    "deviceId": existing["deviceId"],
                    "deviceType": kind,
                    "deviceSecret": result["deviceSecret"],
                }
                persist()
            if existing["assetStatus"] == "REGISTERED":
                existing = call(
                    "/devices/" + existing["deviceId"],
                    {
                        "assetStatus": "IN_SERVICE",
                        "expectedVersion": existing["version"],
                    },
                    method="PATCH",
                    command="commission/" + suffix,
                )
            if existing["assetStatus"] != "IN_SERVICE":
                raise RuntimeError(
                    "An existing asset is stopped or retired; the provisioner will not re-enable it."
                )
            devices.append(credentials[code])

        switch, ipc1, ipc2, nvr = devices
        points = call("/monitoring-points?parkId=CAMPUS-A")
        for index, ipc in enumerate((ipc1, ipc2), 1):
            code = args.prefix + "-POINT" + str(index)
            point = next((p for p in points if p["pointCode"] == code), None)
            point = point or call(
                "/monitoring-points",
                {
                    "pointCode": code,
                    "name": "入口" if index == 1 else "走廊",
                    "areaId": parent,
                },
                command="point/" + str(index),
            )
            bindings = call(
                "/monitoring-points/" + point["monitoringPointId"] + "/bindings"
            )
            current = next((b for b in bindings if b.get("validTo") is None), None)
            if current and current["ipcDeviceId"] != ipc["deviceId"]:
                raise RuntimeError(
                    "Monitoring point is already bound to another IPC; no automatic replacement will be made."
                )
            if not current:
                device = call("/devices/" + ipc["deviceId"])
                call(
                    "/monitoring-points/" + point["monitoringPointId"] + "/bindings",
                    {
                        "ipcDeviceId": ipc["deviceId"],
                        "reason": "建立虚拟设备演示点位",
                        "expectedPointVersion": point["version"],
                        "expectedDeviceVersion": device["version"],
                    },
                    command="bind/" + str(index),
                )
            ipc["monitoringPointId"] = point["monitoringPointId"]

        for index, device in enumerate((ipc1, ipc2, nvr), 1):
            call(
                "/network-dependencies",
                {
                    "upstreamSwitchId": switch["deviceId"],
                    "downstreamDeviceId": device["deviceId"],
                    "upstreamPortRef": str(index),
                    "reason": "建立虚拟设备演示网络关系",
                },
                command="network/" + str(index),
            )
            device["upstreamDeviceId"] = switch["deviceId"]
            if index <= 2:
                call(
                    "/power-dependencies",
                    {
                        "poeSwitchId": switch["deviceId"],
                        "poePort": str(index),
                        "ipcDeviceId": device["deviceId"],
                        "reason": "建立虚拟设备演示供电关系",
                    },
                    command="power/" + str(index),
                )
                call(
                    "/video-channel-associations",
                    {
                        "nvrDeviceId": nvr["deviceId"],
                        "ipcDeviceId": device["deviceId"],
                        "channelNo": index,
                        "reason": "建立虚拟设备演示录像通道",
                    },
                    command="video/" + str(index),
                )
            persist()
        print(
            "Virtual IPC/NVR/switch assets provisioned. Credentials saved locally; no physical device compatibility is claimed."
        )
    finally:
        try:
            call("/auth/logout", {}, method="POST")
        except Exception:
            print(
                "Provisioning session logout was unavailable; the session will expire automatically."
            )


if __name__ == "__main__":
    main()
