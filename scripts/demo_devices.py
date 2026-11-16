"""Register a small demo topology through the public API and save local credentials."""

import argparse
import json
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://localhost:8088")
    parser.add_argument("--prefix", default="DEMO")
    parser.add_argument("--output", type=Path, default=ROOT / "var" / "demo-devices.json")
    args = parser.parse_args()
    values = dict(line.split("=", 1) for line in (ROOT / ".env").read_text().splitlines() if line and not line.startswith("#"))
    token = None

    def call(path, body=None):
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = f"Bearer {token}"
        request = Request(args.base_url + "/api/v1" + path, headers=headers,
                          data=None if body is None else json.dumps(body).encode())
        try:
            with urlopen(request, timeout=20) as response:
                return json.load(response)["data"]
        except HTTPError as error:
            details = json.load(error)
            raise RuntimeError(f"HTTP {error.code}: {details.get('message', 'Request failed')}") from None

    token = call("/auth/login", {"username": "admin", "password": values["DEMO_ADMIN_PASSWORD"]})["accessToken"]
    saved = json.loads(args.output.read_text()) if args.output.exists() else []
    credentials = {device["deviceCode"]: device for device in saved}
    registered = {device["deviceCode"]: device for device in call("/devices?" + urlencode({"keyword": args.prefix, "size": 100}))["items"]}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    for building in range(1, 4):
        upstream = None
        for kind, suffix, label in [("SWITCH", "SW", "接入交换机"), ("CAMERA", "CAM", "走廊摄像头"),
                                     ("ACCESS_CONTROL", "ACS", "入口门禁"), ("SENSOR", "SEN", "环境传感器")]:
            code = f"{args.prefix}-{building}-{suffix}"
            if code in registered:
                if code not in credentials or credentials[code]["deviceId"] != registered[code]["id"]:
                    raise RuntimeError(f"Local credentials missing for {code}. Use a new --prefix to register another demo set.")
                device = credentials[code]
            else:
                result = call("/devices", {"deviceCode": code, "name": f"演示{building}号楼{label}", "type": kind,
                                          "buildingId": f"BLD-{building}", "floorId": f"FL-{building}-1",
                                          "areaId": f"AREA-{building}-1", "upstreamNodeId": upstream})
                asset = result["device"]
                device = {"deviceId": asset["id"], "deviceCode": code, "campusId": asset["campusId"],
                          "type": kind, "secret": result["deviceSecret"]}
                credentials[code] = device
                temporary = args.output.with_suffix(".tmp")
                temporary.write_text(json.dumps(list(credentials.values()), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
                temporary.replace(args.output)
            if kind == "SWITCH":
                upstream = device["deviceId"]
    print(f"Registered/reused 12 demo devices. Local simulator configuration saved to {args.output}")


if __name__ == "__main__":
    main()
