"""Explicit, isolated CN targets. No Docker or Kubernetes calls at import time."""

import ipaddress
import json
import os
import re
import subprocess
from contextlib import contextmanager
from pathlib import Path

from microservices_common import ROOT, private_path, sha, write_json

CLUSTER = "capyiot-cn-verify"
CONTEXT = "kind-" + CLUSTER
NAMESPACE = CLUSTER
INFRA_PROJECT = "capyiot-cn-infra-verify"
BRIDGE = CLUSTER + "-bridge"
SUBNET = "10.250.80.0/24"
NODE_IP = "10.250.80.2"
POD_SUBNET = "10.248.0.0/16"
SERVICE_SUBNET = "10.249.0.0/16"
NODE_IMAGE = (
    "kindest/node:v1.37.0@sha256:"
    "a1ed56cfb0e7b93589bdf97c8cd566405a265939e3620fc4f5de89adff580ae5"
)
KIND_VERSION = "v0.33.0"
KUBECTL_VERSION = "v1.37.0"
INFRA_FILE = ROOT / "infra/k8s/compose.infra.yml"
KIND_FILE = ROOT / "infra/k8s/kind/verify.yaml"
BROKER_FILE = ROOT / "infra/k8s/emqx.conf"
ENDPOINTS = {
    "mysql": ("10.250.80.10", [("mysql", 3306)]),
    "kafka": ("10.250.80.11", [("kafka", 39092)]),
    "emqx": ("10.250.80.12", [("mqtts", 8883), ("management", 18083)]),
}
INFRA_IMAGES = {
    "mysql": "mysql:8.4.11@sha256:85b9bf2e29cf836ecb8c2a15a935d4ba0c606631dff1dd79531a11983c638f2a",
    "kafka": "apache/kafka:4.1.1@sha256:0bc1bb2478f45b6cea78864df86acdc11e8df2c5172477819a4d12942cbe5d40",
    "emqx": "emqx/emqx:5.8.9@sha256:35b46f7aa7a0d51f3959e4344221ac5ec7f948b03e43099286b6c40a07bb393b",
}


def execute(argv, *, data=None, env_values=None):
    """No shell, diagnostics, process environment dumps or captured output in errors."""
    env = {
        key: value
        for key, value in os.environ.items()
        if not key.startswith(("CN_", "MS_", "COMPOSE_", "DEEPSEEK_"))
        and key != "KUBECONFIG"
    }
    if env_values:
        env.update(env_values)
    result = subprocess.run(
        list(map(str, argv)), cwd=ROOT, input=data, env=env,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=600,
    )
    if result.returncode:
        raise RuntimeError("CN command failed; inspect the explicit target before retrying")
    return result.stdout


def safe_file(base, relative):
    base = Path(base).resolve()
    part = Path(relative)
    if part.is_absolute() or ".." in part.parts:
        raise ValueError("Material path escapes its private bundle")
    path = (base / part).resolve()
    if base not in path.parents or not path.is_file():
        raise ValueError("Required material is missing or outside its private bundle")
    return path


def verify_files(base, files):
    if not files:
        raise ValueError("An explicit material inventory is required")
    for relative, expected in files.items():
        if not re.fullmatch(r"[a-f0-9]{64}", expected) or sha(safe_file(base, relative)) != expected:
            raise ValueError("Bundle material changed; never replace identities implicitly")


def material_inventory(bundle):
    files = {}
    for directory in ("keys", "secrets", "pki"):
        for path in sorted((bundle / directory).rglob("*")):
            if path.is_file():
                relative = path.relative_to(bundle).as_posix()
                files[relative] = sha(safe_file(bundle, relative))
    return files


@contextmanager
def operation_lock(bundle):
    path = bundle / ".cn-operation.lock"
    try:
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except FileExistsError:
        raise ValueError("CN operation already active or interrupted; reconcile before retrying") from None
    try:
        os.write(fd, str(os.getpid()).encode())
        os.close(fd)
        yield
    finally:
        path.unlink(missing_ok=True)


def validate_selection(cluster, context, namespace, infra_project):
    if (cluster, context, namespace, infra_project) != (CLUSTER, CONTEXT, NAMESPACE, INFRA_PROJECT):
        raise ValueError("Only the explicitly named isolated CN verification target is supported")


def check_networks(networks, routes):
    planned = [ipaddress.ip_network(x) for x in (SUBNET, POD_SUBNET, SERVICE_SUBNET)]
    for subnet in routes:
        other = ipaddress.ip_network(subnet, strict=False)
        if other.version == 4 and other.prefixlen and any(other.overlaps(x) for x in planned):
            raise ValueError("Planned CN subnets overlap a host route")
    for network in networks:
        for config in network.get("IPAM", {}).get("Config") or []:
            if not config.get("Subnet"):
                continue
            other = ipaddress.ip_network(config["Subnet"], strict=False)
            if network["Name"] == BRIDGE:
                continue  # Ownership and exact subnet are checked against the bound network ID.
            if other.version == 4 and any(other.overlaps(x) for x in planned):
                raise ValueError("Planned CN subnets overlap an existing Docker network")


def check_bridge(network, owner_id, bound_id=None):
    configs = network.get("IPAM", {}).get("Config") or []
    if (
        network["Name"] != BRIDGE
        or (network.get("Labels") or {}).get("io.capyiot.cn.owner") != owner_id
        or [x.get("Subnet") for x in configs] != [SUBNET]
        or (bound_id is not None and network["Id"] != bound_id)
    ):
        raise ValueError("Bridge identity or ownership does not match the CN bundle")


def check_infra(objects, network_id, *, require_healthy=True, previous=None):
    found = {}
    for obj in objects:
        labels = obj["Config"].get("Labels") or {}
        service = labels.get("com.docker.compose.service")
        network = (obj["NetworkSettings"].get("Networks") or {}).get(BRIDGE, {})
        if (
            labels.get("com.docker.compose.project") != INFRA_PROJECT
            or service not in ENDPOINTS or service in found
            or obj["Config"].get("Image") != INFRA_IMAGES.get(service)
            or network.get("NetworkID") != network_id
            or network.get("IPAddress") != ENDPOINTS[service][0]
            or not obj["State"]["Running"]
            or (require_healthy and obj["State"].get("Health", {}).get("Status") != "healthy")
        ):
            raise ValueError("External infrastructure identity, address or health does not match")
        expected_volumes = {INFRA_PROJECT + "_" + service + "-data"}
        actual_volumes = {m["Name"] for m in (obj.get("Mounts") or []) if m["Type"] == "volume"}
        if actual_volumes != expected_volumes:
            raise ValueError("External infrastructure uses an unexpected data volume")
        found[service] = {"containerId": obj["Id"], "imageId": obj["Image"], "address": network["IPAddress"]}
    if set(found) != set(ENDPOINTS):
        raise ValueError("Exactly three owned infrastructure containers are required")
    if previous is not None and previous != found:
        raise ValueError("Bound infrastructure containers changed; endpoint updates require reconciliation")
    return found


def endpoint_objects(containers):
    """Only called after all addresses have been checked against bound container identities."""
    if set(containers) != set(ENDPOINTS):
        raise ValueError("Missing verified infrastructure identities")
    result = []
    for service, (address, ports) in ENDPOINTS.items():
        if containers[service]["address"] != address or not containers[service].get("containerId"):
            raise ValueError("Unverified endpoint address")
        metadata = {"name": service, "namespace": NAMESPACE,
                    "labels": {"app.kubernetes.io/part-of": "capyiot", "io.capyiot.cn/target": CLUSTER}}
        result.append({"apiVersion": "v1", "kind": "Service", "metadata": metadata,
                       "spec": {"ports": [{"name": n, "port": p, "targetPort": p} for n, p in ports]}})
        result.append({"apiVersion": "discovery.k8s.io/v1", "kind": "EndpointSlice",
                       "metadata": {**metadata, "name": service + "-external", "labels": {
                           **metadata["labels"], "kubernetes.io/service-name": service,
                           "endpointslice.kubernetes.io/managed-by": "capyiot-cn-tool"}},
                       "addressType": "IPv4", "ports": [{"name": n, "port": p, "protocol": "TCP"} for n, p in ports],
                       "endpoints": [{"addresses": [address], "conditions": {"ready": True}}]})
    return {"apiVersion": "v1", "kind": "List", "items": result}


class Target:
    def __init__(self, bundle, *, cluster, context, namespace, infra_project):
        validate_selection(cluster, context, namespace, infra_project)
        self.bundle = private_path(bundle)
        self.manifest = json.loads((self.bundle / "cn-manifest.json").read_text(encoding="utf-8"))
        if (
            self.manifest.get("formatVersion") != 1 or self.manifest.get("status") != "READY"
            or self.manifest.get("target") != {
                "cluster": CLUSTER, "context": CONTEXT, "namespace": NAMESPACE, "infraProject": INFRA_PROJECT}
            or self.manifest.get("bundle") != self.bundle.as_posix()
        ):
            raise ValueError("CN manifest and explicit target must match")
        verify_files(self.bundle, self.manifest["files"])
        if self.manifest.get("resourceProfile","standard") not in {"standard","compact"}:
            raise ValueError("Unsupported frozen resource profile")
        for relative, expected in self.manifest["sourceFiles"].items():
            if sha(safe_file(ROOT, relative)) != expected:
                raise ValueError("Infrastructure source changed after preparation; review a new bundle")

    def compose(self, *args):
        command = ["docker", "compose", "--project-directory", str(ROOT), "-p", INFRA_PROJECT,
                   "--env-file", str(self.bundle / "infra.env"), "-f", str(INFRA_FILE)]
        if self.manifest.get("resourceProfile") == "compact":
            command += ["-f", str(ROOT / "infra/k8s/compose.compact.yml")]
        return [*command, *args]

    def kubectl(self, *args):
        return self.client(*args, configured=True)

    def client(self, *args, configured=False):
        if self.manifest.get("kubectlMode", "local") == "node-image":
            # Only the selected kubeconfig is mounted; never the bundle, signing keys or CA private key.
            command=["docker","run","--rm","-i","--network","host","--memory","128m"]
            if configured:
                command += ["--mount","type=bind,source="+(self.bundle/"kubeconfig").as_posix()+",target=/run/cn-kubeconfig,readonly"]
            command += ["--entrypoint","/usr/bin/kubectl",NODE_IMAGE]
            config_path="/run/cn-kubeconfig"
        else:
            command=[self.manifest["kubectlBinary"]]
            config_path=str(self.bundle/"kubeconfig")
        if configured:
            command += ["--kubeconfig",config_path,"--context",CONTEXT,"--namespace",NAMESPACE]
        return [*command,*args]

    def state(self):
        path = self.bundle / "infrastructure-state.json"
        if not path.exists():
            return {}
        value = json.loads(path.read_text(encoding="utf-8"))
        if value.get("ownerId") != self.manifest["ownerId"]:
            raise ValueError("Infrastructure state belongs to a different bundle")
        return value

    def assert_cluster(self, state):
        verify_files(self.bundle, {"kubeconfig": state["kubeconfigSha256"]})
        nodes = json.loads(execute(["docker", "inspect", CLUSTER + "-control-plane"]))
        node = nodes[0]
        bridge = (node["NetworkSettings"].get("Networks") or {}).get(BRIDGE, {})
        if (
            node["Id"] != state["nodeId"] or node["Image"] != state["nodeImageId"]
            or (node["Config"].get("Labels") or {}).get("io.x-k8s.kind.cluster") != CLUSTER
            or bridge.get("NetworkID") != state["networkId"]
            or bridge.get("IPAddress") != NODE_IP
        ):
            raise ValueError("Kind node identity changed")
        config = json.loads(execute(self.kubectl("config", "view", "--minify", "-o", "json")))
        if config["clusters"][0]["cluster"]["server"] != state["apiServer"]:
            raise ValueError("Kubernetes API server changed")
        ns = json.loads(execute(self.kubectl("get", "namespace", "kube-system", "-o", "json")))
        if ns["metadata"]["uid"] != state["clusterUid"]:
            raise ValueError("Kubernetes cluster UID changed")

    def assert_daemon(self, state):
        info = json.loads(execute(["docker", "info", "--format", "{{json .}}"] ))
        if info["ID"] != state["dockerId"]:
            raise ValueError("Docker daemon differs from the bound infrastructure target")


def save_state(target, value):
    write_json(target.bundle / "infrastructure-state.json", {"ownerId": target.manifest["ownerId"], **value})
