"""Prepare isolated CN material and infrastructure. Explicit execution; never activates writers."""

import argparse
import base64
import contextlib
import io
import json
import os
import re
import socket
import uuid
from pathlib import Path
from types import SimpleNamespace
from urllib.parse import urlsplit

import cloud_native_common as cn
import init_microservices as ms_init
from microservices_common import APPS, ROOT, private_path, read_env, sha, write_json


def prepare(args):
    cn.validate_selection(args.cluster, args.context, args.namespace, args.infra_project)
    if args.mode not in {"fresh", "import"} or args.model_mode not in {"offline", "real"}:
        raise ValueError("Explicit supported credential and model modes are required")
    bundle = private_path(args.bundle)
    if any(char in bundle.as_posix() for char in ("\n", "\r", "\x00", "$", "'", '"', "#")):
        raise ValueError("Bundle path cannot be represented safely in the explicit environment")
    if bundle.exists():
        raise ValueError("Preparation requires a new bundle directory; never overwrite existing identities")
    source = None
    if args.mode == "import":
        source = private_path(args.source_bundle)
        source_manifest = json.loads((source / "manifest.json").read_text(encoding="utf-8"))
        if source_manifest.get("status") != "READY" or source_manifest.get("modelMode") != args.model_mode:
            raise ValueError("Import needs a complete native bundle with the selected model mode")
        cn.verify_files(source, source_manifest["files"])
        required = ["pki/ca.key", "pki/ca.pem", "pki/edge/ca.pem", "pki/edge/server.pem", "pki/edge/server.key",
                    "keys/user-keys.json", "keys/device-keys.json", "keys/agent-keys.json",
                    *["keys/" + app + ".json" for app in APPS],
                    "secrets/tls-password", "secrets/mysql-root-password", "secrets/deepseek-api-key",
                    "secrets/session-proxy-key", "secrets/emqx-api-key", "secrets/emqx-api-secret",
                    "secrets/emqx-api-keys", "secrets/emqx-node-cookie",
                    *["secrets/" + d + "-db-password" for d in ("device", "alarm", "workflow", "platform", "agent")],
                    *["secrets/" + d + "-fingerprint-key" for d in ("device", "fault", "workflow", "platform")]]
        if not set(required).issubset(source_manifest["files"]):
            raise ValueError("Native source manifest lacks required identities or credentials")
        values = read_env(source / "deployment.env")
        if values["MS_PROJECT"] != source_manifest["project"]:
            raise ValueError("Native source environment identity mismatch")
        model_origin = urlsplit(values["DEEPSEEK_BASE_URL"])
        if model_origin.username or model_origin.password or model_origin.query or model_origin.fragment:
            raise ValueError("Model origin must not contain embedded credentials, query or fragment")
    elif args.source_bundle is not None:
        raise ValueError("Fresh mode must not read a source bundle")
    # Atomic reservation: a concurrent preparation of the same target fails before minting keys.
    bundle.mkdir(parents=True, exist_ok=False, mode=0o700)
    bundle.chmod(0o700)
    # A failed preparation deliberately leaves its partial files for inspection.
    if source is None:
        with contextlib.redirect_stdout(io.StringIO()):
            ms_init.initialize(SimpleNamespace(
                project=cn.INFRA_PROJECT, bundle=bundle, mode="fresh", volume_prefix=cn.INFRA_PROJECT,
                model_mode=args.model_mode, source_project=None, legacy_env=None,
                legacy_tls=None, import_keys=None, tls_namespace=cn.NAMESPACE,
            ))
        values = read_env(bundle / "deployment.env")
        source_identity = None
    else:
        # Copy credential/identity files by the recorded hash, never by source container paths.
        for relative in source_manifest["files"]:
            if Path(relative).parts[0] in {"keys", "secrets"} or relative in {"pki/ca.key", "pki/ca.pem"}:
                ms_init.save(bundle / relative, cn.safe_file(source, relative).read_bytes(), private=True)
        password = (bundle / "secrets/tls-password").read_text(encoding="utf-8")
        # Keep both original trust anchors and edge leaf; issue candidate internal leaves from the original CA.
        ms_init.provision_tls(bundle, password, source / "pki/edge", namespace=cn.NAMESPACE)
        cn.verify_files(source, source_manifest["files"])
        source_identity = {"project": source_manifest["project"], "manifestSha256": sha(source / "manifest.json")}
    (bundle / "kind.yaml").write_bytes(cn.KIND_FILE.read_bytes())
    # This environment contains paths and target metadata only, never passwords or signing keys.
    cluster_id = base64.urlsafe_b64encode(uuid.uuid4().bytes).decode().rstrip("=")
    env = {"CN_INFRA_PROJECT": cn.INFRA_PROJECT, "CN_BUNDLE": bundle.as_posix(), "CN_KAFKA_CLUSTER_ID": cluster_id}
    ms_init.save(bundle / "infra.env", "".join(k + "=" + v + "\n" for k, v in env.items()), private=True)
    ms_init.save(bundle / "simulator-target.json", json.dumps({
        "platformUrl": "https://frontend:30443", "mqttUrl": "ssl://emqx:8883",
        "caFile": (bundle / "pki/edge/ca.pem").as_posix(),
        "extraHosts": {"frontend": cn.NODE_IP}, "network": cn.BRIDGE,
        "ingestionClientId": "capyiot-ingestion-cn-verify-1",
        "deviceFile": (bundle / "video-devices.json").as_posix(),
    }, indent=2), private=True)
    files = cn.material_inventory(bundle)
    for filename in ("kind.yaml", "infra.env", "simulator-target.json"):
        files[filename] = sha(bundle / filename)
    for name in ("frontend", "emqx"):
        from cryptography import x509
        cert = x509.load_pem_x509_certificate((bundle / "pki/edge/server.pem").read_bytes())
        if name not in cert.extensions.get_extension_for_class(x509.SubjectAlternativeName).value.get_values_for_type(x509.DNSName):
            raise ValueError("Imported edge certificate lacks the required short-name SAN")
    write_json(bundle / "cn-manifest.json", {
        "formatVersion": 1, "status": "READY", "ownerId": uuid.uuid4().hex,
        "target": {"cluster": cn.CLUSTER, "context": cn.CONTEXT, "namespace": cn.NAMESPACE, "infraProject": cn.INFRA_PROJECT},
        "bundle": bundle.as_posix(), "mode": args.mode, "modelMode": args.model_mode,
        "model": {"id": values["DEEPSEEK_MODEL"], "baseUrl": values["DEEPSEEK_BASE_URL"]},
        "source": source_identity, "files": files,
        "sourceFiles": {p.relative_to(ROOT).as_posix(): sha(p) for p in (
            cn.INFRA_FILE, cn.KIND_FILE, cn.BROKER_FILE, ROOT / "infra/mysql/01-microservices.sh",
            ROOT / "infra/emqx/start-microservices.sh", ROOT / "scripts/cloud_native_common.py",
            ROOT / "scripts/cloud_native_environment.py", ROOT / "scripts/init_microservices.py",
            ROOT / "scripts/cloud_native_identity.py",
            ROOT / "scripts/microservices_common.py",
            ROOT / "infra/k8s/compose.compact.yml",
        )},
        "kindBinary": str(Path(args.kind_binary).resolve()),
        "kubectlBinary": str(Path(args.kubectl_binary).resolve()),
        "versions": {"kind": cn.KIND_VERSION, "kubectl": cn.KUBECTL_VERSION, "nodeImage": cn.NODE_IMAGE},
        "writersEnabled": False,
        "resourceProfile": getattr(args, "resource_profile", "standard"),
        "kubectlMode": getattr(args,"kubectl_mode","local"),
    })


def host_routes():
    if os.name == "nt":
        value = json.loads(cn.execute(["powershell.exe", "-NoProfile", "-Command",
            "Get-NetRoute -AddressFamily IPv4 | Select-Object -ExpandProperty DestinationPrefix | ConvertTo-Json -Compress"]))
        return [value] if isinstance(value, str) else value
    value = json.loads(cn.execute(["ip", "-j", "-4", "route", "show", "table", "all"]))
    return [r["dst"] for r in value if r.get("dst") not in {None, "default"}]


def memory_budget(info, required_free_gib, profile="standard"):
    minimum = 7.3 if profile == "compact" else 12
    if info["OSType"] != "linux" or info["MemTotal"] < minimum * 2**30:
        raise ValueError("CN runtime requires the frozen Linux Docker resource budget")
    stats = cn.execute(["docker", "stats", "--no-stream", "--format", "{{json .}}"])
    used = 0
    units = {"B": 1, "kB": 1000, "MB": 1000**2, "GB": 1000**3,
             "KiB": 1024, "MiB": 1024**2, "GiB": 1024**3}
    for line in stats.decode().splitlines():
        value = json.loads(line)["MemUsage"].split("/")[0].strip()
        match = re.fullmatch(r"([0-9.]+)\s*(B|kB|MB|GB|KiB|MiB|GiB)", value)
        if not match:
            raise ValueError("Cannot determine available Docker memory")
        used += float(match[1]) * units[match[2]]
    if info["MemTotal"] - used < required_free_gib * 2**30:
        raise ValueError("Insufficient Docker memory remaining for the frozen CN stage budget")
    if os.name == "nt":
        import ctypes

        class MemoryStatus(ctypes.Structure):
            _fields_ = [("length", ctypes.c_ulong), ("load", ctypes.c_ulong),
                        *[(n, ctypes.c_ulonglong) for n in ("total", "available", "pageTotal", "pageAvailable", "virtualTotal", "virtualAvailable", "extended")]]

        memory = MemoryStatus()
        memory.length = ctypes.sizeof(memory)
        if not ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(memory)) or memory.available < 2 * 2**30:
            raise ValueError("Host memory headroom is below the frozen Windows safety budget")


def preflight(target):
    info = json.loads(cn.execute(["docker", "info", "--format", "{{json .}}"] ))
    profile = target.manifest.get("resourceProfile", "standard")
    memory_budget(info, 6.2 if profile == "compact" else 10, profile)
    if cn.KIND_VERSION not in cn.execute([target.manifest["kindBinary"], "version"]).decode().split():
        raise ValueError("Kind binary does not match the frozen version")
    client = json.loads(cn.execute(target.client("version","--client","-o","json")))
    if client["clientVersion"]["gitVersion"] != cn.KUBECTL_VERSION:
        raise ValueError("Kubernetes API client does not match the frozen version")
    # The exact node image must already have been acquired and verified in the unified preparation step.
    image = json.loads(cn.execute(["docker", "image", "inspect", cn.NODE_IMAGE]))[0]
    net_ids = cn.execute(["docker", "network", "ls", "-q"]).decode().split()
    networks = json.loads(cn.execute(["docker", "network", "inspect", *net_ids])) if net_ids else []
    cn.check_networks(networks, host_routes())
    return info, image, networks


def create_cluster(target):
    if target.manifest["mode"] != "fresh":
        raise ValueError("Imported credentials cannot bootstrap a new authority; use the later controlled adoption flow")
    if target.state():
        raise ValueError("Infrastructure operation already recorded; reconcile it instead of recreating")
    info, image, networks = preflight(target)
    if any(n["Name"] == cn.BRIDGE for n in networks):
        raise ValueError("A bridge with this name already exists and is not bound to this operation")
    existing = cn.execute(["docker", "ps", "-a", "--filter", "label=io.x-k8s.kind.cluster=" + cn.CLUSTER, "-q"]).decode().strip()
    volumes = cn.execute(["docker", "volume", "ls", "--format", "{{.Name}}" ]).decode().splitlines()
    infra = cn.execute(["docker", "ps", "-a", "--filter", "label=com.docker.compose.project=" + cn.INFRA_PROJECT, "-q"]).decode().strip()
    if existing or infra or any(x.startswith(cn.INFRA_PROJECT + "_") for x in volumes):
        raise ValueError("Fresh CN target already has nodes, containers or volumes")
    for port in (28088, 28443, 28883):
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", port))
    state = {"phase": "CREATING", "dockerId": info["ID"], "nodeImageId": image["Id"]}
    cn.save_state(target, state)
    # Failure leaves CREATING and all resources intact; no automatic destructive retry or cleanup.
    cn.execute(["docker", "network", "create", "--driver", "bridge", "--subnet", cn.SUBNET,
                "--gateway", "10.250.80.1", "--label", "io.capyiot.cn.owner=" + target.manifest["ownerId"], cn.BRIDGE])
    cn.execute([target.manifest["kindBinary"], "create", "cluster", "--name", cn.CLUSTER,
                "--image", cn.NODE_IMAGE, "--config", str(target.bundle / "kind.yaml"),
                "--kubeconfig", str(target.bundle / "kubeconfig"), "--wait", "180s"])
    (target.bundle / "kubeconfig").chmod(0o600)
    node = json.loads(cn.execute(["docker", "inspect", cn.CLUSTER + "-control-plane"]))[0]
    if (node["Config"].get("Labels") or {}).get("io.x-k8s.kind.cluster") != cn.CLUSTER or node["Image"] != image["Id"]:
        raise ValueError("Created Kind node does not match the pinned target")
    cn.execute(["docker", "network", "connect", "--ip", cn.NODE_IP, cn.BRIDGE, node["Id"]])
    # Explicit, node-local SNAT for Pods reaching the external bridge. No host/global routes change.
    cn.execute(["docker", "exec", node["Id"], "iptables", "-t", "nat", "-I", "POSTROUTING", "1",
                "-s", cn.POD_SUBNET, "-d", cn.SUBNET, "-j", "SNAT", "--to-source", cn.NODE_IP])
    network = json.loads(cn.execute(["docker", "network", "inspect", cn.BRIDGE]))[0]
    cn.check_bridge(network, target.manifest["ownerId"])
    config = json.loads(cn.execute(target.kubectl("config", "view", "--minify", "-o", "json")))
    api = config["clusters"][0]["cluster"]["server"]
    if not api.startswith("https://127.0.0.1:"):
        raise ValueError("Kind API must use the explicitly private loopback endpoint")
    ns = json.loads(cn.execute(target.kubectl("get", "namespace", "kube-system", "-o", "json")))
    from cloud_native_identity import install_deployer
    install_deployer(target)
    state.update(phase="CLUSTER_BOUND", nodeId=node["Id"], networkId=network["Id"],
                 kubeconfigSha256=sha(target.bundle / "kubeconfig"), apiServer=api, clusterUid=ns["metadata"]["uid"])
    cn.save_state(target, state)


def start_infra(target):
    if target.manifest["mode"] != "fresh":
        raise ValueError("Imported identities require controlled adoption, never fresh volume initialization")
    state = target.state()
    if state.get("phase") != "CLUSTER_BOUND":
        raise ValueError("A newly bound cluster is required before infrastructure initialization")
    target.assert_daemon(state)
    target.assert_cluster(state)
    profile = target.manifest.get("resourceProfile", "standard")
    memory_budget(json.loads(cn.execute(["docker", "info", "--format", "{{json .}}"])), 5.3 if profile == "compact" else 8, profile)
    network = json.loads(cn.execute(["docker", "network", "inspect", cn.BRIDGE]))[0]
    cn.check_bridge(network, target.manifest["ownerId"], state["networkId"])
    volumes = cn.execute(["docker", "volume", "ls", "--format", "{{.Name}}"] ).decode().splitlines()
    existing = cn.execute(["docker", "ps", "-a", "--filter", "label=com.docker.compose.project=" + cn.INFRA_PROJECT, "-q"]).decode().strip()
    if existing or any(x.startswith(cn.INFRA_PROJECT + "_") for x in volumes):
        raise ValueError("Refusing to initialize already existing infrastructure authority")
    cn.save_state(target, {**state, "phase": "INFRA_STARTING"})
    cn.execute(target.compose("up", "-d", "--wait", "--wait-timeout", "180"),
               env_values=read_env(target.bundle / "infra.env"))
    ids = cn.execute(["docker", "ps", "-a", "--filter", "label=com.docker.compose.project=" + cn.INFRA_PROJECT, "-q"]).decode().split()
    containers = cn.check_infra(json.loads(cn.execute(["docker", "inspect", *ids])), state["networkId"])
    write_json(target.bundle / "external-services.json", cn.endpoint_objects(containers))
    cn.save_state(target, {**state, "phase": "INFRA_READY", "containers": containers,
                          "endpointsSha256": sha(target.bundle / "external-services.json")})


def verified_endpoints(target):
    """Recheck IDs immediately before the release layer publishes these addresses to Kubernetes."""
    state = target.state()
    if state.get("phase") != "INFRA_READY":
        raise ValueError("Infrastructure is not ready")
    target.assert_daemon(state)
    target.assert_cluster(state)
    network = json.loads(cn.execute(["docker", "network", "inspect", cn.BRIDGE]))[0]
    cn.check_bridge(network, target.manifest["ownerId"], state["networkId"])
    ids = cn.execute(["docker", "ps", "-a", "--filter", "label=com.docker.compose.project=" + cn.INFRA_PROJECT, "-q"]).decode().split()
    if not ids:
        raise ValueError("Owned infrastructure containers are missing")
    objects = json.loads(cn.execute(["docker", "inspect", *ids]))
    found = cn.check_infra(objects, state["networkId"], previous=state["containers"])
    cn.verify_files(target.bundle, {"external-services.json": state["endpointsSha256"]})
    return cn.endpoint_objects(found)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["plan", "prepare", "preflight", "create-cluster", "start-infra"])
    parser.add_argument("--bundle", type=Path, required=True)
    for key in ("cluster", "context", "namespace", "infra-project"):
        parser.add_argument("--" + key, required=True)
    parser.add_argument("--mode", choices=["fresh", "import"], default="fresh")
    parser.add_argument("--model-mode", choices=["offline", "real"], default="offline")
    parser.add_argument("--source-bundle", type=Path)
    parser.add_argument("--resource-profile", choices=["standard", "compact"], default="standard")
    parser.add_argument("--kubectl-mode",choices=["local","node-image"],default="local")
    parser.add_argument("--kind-binary", type=Path, default=ROOT / "var/cloud-native/tools" / ("kind.exe" if os.name == "nt" else "kind"))
    parser.add_argument("--kubectl-binary", type=Path, default=ROOT / "var/cloud-native/tools" / ("kubectl.exe" if os.name == "nt" else "kubectl"))
    parser.add_argument("--execute", action="store_true")
    args = parser.parse_args()
    try:
        cn.validate_selection(args.cluster, args.context, args.namespace, args.infra_project)
        if args.action == "plan" or not args.execute:
            print(json.dumps({"action": args.action, "execution": False, "cluster": cn.CLUSTER,
                              "namespace": cn.NAMESPACE, "infraProject": cn.INFRA_PROJECT,
                              "bundle": str(private_path(args.bundle)), "writersEnabled": False}))
        elif args.action == "prepare":
            prepare(args)
            print("Prepared private CN material; no containers, cluster, database or writers were started")
        else:
            target = cn.Target(args.bundle, cluster=args.cluster, context=args.context,
                               namespace=args.namespace, infra_project=args.infra_project)
            with cn.operation_lock(target.bundle):
                if args.action == "preflight":
                    preflight(target)
                elif args.action == "create-cluster":
                    create_cluster(target)
                else:
                    start_infra(target)
            print("CN infrastructure action completed; no application writers were activated")
    except (ValueError, RuntimeError, OSError, KeyError, TypeError):
        # Never echo exception text from parsers, TLS or subprocesses that may contain material.
        parser.exit(1, "CN action refused or failed; inspect the selected private target and its recorded phase\n")


if __name__ == "__main__":
    main()
