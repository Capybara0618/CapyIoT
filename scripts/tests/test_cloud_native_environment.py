"""CN environment boundary tests; run with the unified deployment checks after code freeze."""

import contextlib
import io
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import cloud_native_common as cn
import cloud_native_environment as environment
import init_microservices as ms_init
from microservices_common import ROOT, sha, write_json


class CloudNativeEnvironmentBoundaries(unittest.TestCase):
    def setUp(self):
        (ROOT / "var").mkdir(exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=ROOT / "var")
        self.root = Path(self.temp.name)
        self.addCleanup(self.temp.cleanup)

    def args(self, **changes):
        values = dict(bundle=self.root / "candidate", cluster=cn.CLUSTER, context=cn.CONTEXT,
                      namespace=cn.NAMESPACE, infra_project=cn.INFRA_PROJECT,
                      mode="fresh", model_mode="offline", source_bundle=None,
                      kind_binary=self.root / "kind", kubectl_binary=self.root / "kubectl")
        values.update(changes)
        return SimpleNamespace(**values)

    def fixture(self):
        bundle = self.root / "candidate"
        bundle.mkdir()
        (bundle / "credential").write_text("local-test-credential")
        value = {"formatVersion": 1, "status": "READY", "ownerId": "owner",
                 "target": {"cluster": cn.CLUSTER, "context": cn.CONTEXT,
                            "namespace": cn.NAMESPACE, "infraProject": cn.INFRA_PROJECT},
                 "bundle": bundle.as_posix(), "files": {"credential": sha(bundle / "credential")},
                 "sourceFiles": {}, "mode": "fresh", "kubectlBinary": "test-kubectl"}
        write_json(bundle / "cn-manifest.json", value)
        return bundle

    def target(self, bundle):
        return cn.Target(bundle, cluster=cn.CLUSTER, context=cn.CONTEXT,
                         namespace=cn.NAMESPACE, infra_project=cn.INFRA_PROJECT)

    def containers(self):
        return [{"Id": "container-" + name, "Image": "sha256:" + name,
                 "Config": {"Image": cn.INFRA_IMAGES[name], "Labels": {
                     "com.docker.compose.project": cn.INFRA_PROJECT, "com.docker.compose.service": name}},
                 "State": {"Running": True, "Health": {"Status": "healthy"}},
                 "NetworkSettings": {"Networks": {cn.BRIDGE: {"NetworkID": "network", "IPAddress": address}}},
                 "Mounts": [{"Type": "volume", "Name": cn.INFRA_PROJECT + "_" + name + "-data"}]}
                for name, (address, _) in cn.ENDPOINTS.items()]

    def test_wrong_selection_is_refused_before_bundle_or_docker_access(self):
        for change in ({"context": "kind-capyiot-m5"}, {"namespace": "default"},
                       {"infra_project": "capyiot-ms-experience"}, {"cluster": "capyiot-m5"}):
            with self.subTest(change=change), patch.object(cn, "execute") as execute:
                with self.assertRaises(ValueError):
                    environment.prepare(self.args(**change))
                execute.assert_not_called()
                self.assertFalse((self.root / "candidate").exists())

    def test_changed_identity_is_not_replaced_on_reopen(self):
        bundle = self.fixture()
        (bundle / "credential").write_text("changed-secret")
        with self.assertRaises(ValueError):
            self.target(bundle)
        self.assertEqual((bundle / "credential").read_text(), "changed-secret")

    def test_container_client_mounts_only_kubeconfig_not_private_material(self):
        target=self.target(self.fixture())
        target.manifest["kubectlMode"]="node-image"
        command=target.kubectl("get","pods")
        mount=command[command.index("--mount")+1]
        self.assertIn("/kubeconfig,target=/run/cn-kubeconfig,readonly",mount)
        self.assertNotIn("secrets",mount)
        self.assertIn(cn.NODE_IMAGE,command)
        self.assertEqual(command[-2:],["get","pods"])

    def test_client_version_check_needs_no_cluster_or_key_mount(self):
        target=self.target(self.fixture())
        target.manifest["kubectlMode"]="node-image"
        self.assertNotIn("--mount",target.client("version","--client"))

    def test_copied_bundle_cannot_point_to_a_different_path(self):
        bundle = self.fixture()
        value = json.loads((bundle / "cn-manifest.json").read_text())
        value["bundle"] = (self.root / "another-target").as_posix()
        write_json(bundle / "cn-manifest.json", value)
        with self.assertRaises(ValueError):
            self.target(bundle)

    def test_material_inventory_rejects_traversal(self):
        bundle = self.fixture()
        with self.assertRaises(ValueError):
            cn.verify_files(bundle, {"../candidate/credential": sha(bundle / "credential")})

    def test_changed_cluster_uid_is_refused_even_with_same_context_and_node(self):
        bundle = self.fixture()
        (bundle / "kubeconfig").write_text("private-test-config")
        target = self.target(bundle)
        state = {"kubeconfigSha256": sha(bundle / "kubeconfig"), "nodeId": "same-node",
                 "nodeImageId": "same-image", "networkId": "network", "apiServer": "https://127.0.0.1:54321", "clusterUid": "original-uid"}
        replies = [json.dumps([{"Id": "same-node", "Image": "same-image", "Config": {
            "Labels": {"io.x-k8s.kind.cluster": cn.CLUSTER}}, "NetworkSettings": {
                "Networks": {cn.BRIDGE: {"NetworkID": "network", "IPAddress": cn.NODE_IP}}}}]).encode(),
            json.dumps({"clusters": [{"cluster": {"server": state["apiServer"]}}]}).encode(),
            json.dumps({"metadata": {"uid": "replaced-cluster-uid"}}).encode()]
        with patch.object(cn, "execute", side_effect=replies):
            with self.assertRaises(ValueError):
                target.assert_cluster(state)

    def test_wrong_docker_daemon_is_refused(self):
        target = self.target(self.fixture())
        with patch.object(cn, "execute", return_value=b'{"ID":"another-daemon"}'):
            with self.assertRaises(ValueError):
                target.assert_daemon({"dockerId": "bound-daemon"})

    def test_parallel_operations_cannot_share_one_target(self):
        bundle = self.fixture()
        with cn.operation_lock(bundle):
            with self.assertRaises(ValueError):
                with cn.operation_lock(bundle):
                    self.fail("Second operation entered the same bundle")

    def test_existing_and_partial_bundles_are_never_reinitialized(self):
        bundle = self.fixture()
        before = (bundle / "cn-manifest.json").read_bytes()
        with patch.object(ms_init, "initialize") as initialize:
            with self.assertRaises(ValueError):
                environment.prepare(self.args(bundle=bundle))
            initialize.assert_not_called()
        self.assertEqual((bundle / "cn-manifest.json").read_bytes(), before)

    def test_prepare_requires_execute_and_dry_plan_does_not_read_source(self):
        argv = ["cloud_native_environment.py", "prepare", "--bundle", str(self.root / "absent"),
                "--cluster", cn.CLUSTER, "--context", cn.CONTEXT, "--namespace", cn.NAMESPACE,
                "--infra-project", cn.INFRA_PROJECT, "--source-bundle", str(self.root / "secret-source")]
        with patch.object(sys, "argv", argv), patch.object(cn, "execute") as execute, \
                patch.object(environment, "prepare") as prepare, contextlib.redirect_stdout(io.StringIO()) as output:
            environment.main()
        self.assertFalse(json.loads(output.getvalue())["execution"])
        prepare.assert_not_called()
        execute.assert_not_called()
        self.assertFalse((self.root / "absent").exists())

    def test_host_or_docker_subnet_overlap_blocks_before_creation(self):
        with self.assertRaises(ValueError):
            cn.check_networks([], ["10.250.0.0/16"])
        with self.assertRaises(ValueError):
            cn.check_networks([{"Name": "another-project", "IPAM": {"Config": [{"Subnet": "10.248.1.0/24"}]}}], [])
        cn.check_networks([], ["0.0.0.0/0", "172.28.16.0/20"])

    def test_bridge_name_alone_is_not_ownership(self):
        network = {"Name": cn.BRIDGE, "Id": "network", "Labels": {"io.capyiot.cn.owner": "other"},
                   "IPAM": {"Config": [{"Subnet": cn.SUBNET}]}}
        with self.assertRaises(ValueError):
            cn.check_bridge(network, "owner")
        network["Labels"]["io.capyiot.cn.owner"] = "owner"
        with self.assertRaises(ValueError):
            cn.check_bridge(network, "owner", "old-network")

    def test_wrong_project_volume_image_or_ip_cannot_become_an_endpoint(self):
        for field in ("project", "volume", "image", "ip", "network", "health"):
            objects = self.containers()
            obj = objects[0]
            if field == "project":
                obj["Config"]["Labels"]["com.docker.compose.project"] = "capyiot-ms-experience"
            elif field == "volume":
                obj["Mounts"][0]["Name"] = "capyiot_mysql-data"
            elif field == "image":
                obj["Config"]["Image"] = "unrelated:latest"
            elif field == "ip":
                obj["NetworkSettings"]["Networks"][cn.BRIDGE]["IPAddress"] = "127.0.0.1"
            elif field == "network":
                obj["NetworkSettings"]["Networks"][cn.BRIDGE]["NetworkID"] = "different-network"
            else:
                obj["State"]["Health"]["Status"] = "unhealthy"
            with self.subTest(field=field), self.assertRaises(ValueError):
                cn.check_infra(objects, "network")

    def test_recreated_container_at_same_ip_requires_reconciliation(self):
        objects = self.containers()
        before = cn.check_infra(objects, "network")
        objects[0]["Id"] = "replacement-container"
        with self.assertRaises(ValueError):
            cn.check_infra(objects, "network", previous=before)

    def test_selectorless_endpoints_keep_matching_names_and_no_pod_selector(self):
        value = cn.endpoint_objects(cn.check_infra(self.containers(), "network"))
        services = [x for x in value["items"] if x["kind"] == "Service"]
        slices = [x for x in value["items"] if x["kind"] == "EndpointSlice"]
        self.assertEqual(len(services), 3)
        self.assertTrue(all("selector" not in x["spec"] for x in services))
        kafka = next(x for x in slices if x["metadata"]["labels"]["kubernetes.io/service-name"] == "kafka")
        self.assertEqual(kafka["ports"][0]["port"], 39092)
        self.assertEqual(kafka["endpoints"][0]["addresses"], ["10.250.80.11"])

    def test_resource_shortage_blocks_before_kind_or_network_changes(self):
        target = self.target(self.fixture())
        info = {"OSType": "linux", "MemTotal": 7948341248}
        with patch.object(cn, "execute", return_value=json.dumps(info).encode()) as execute:
            with self.assertRaises(ValueError):
                environment.preflight(target)
        self.assertEqual(execute.call_count, 1)

    def test_imported_credentials_never_initialize_fresh_authority(self):
        target = self.target(self.fixture())
        target.manifest["mode"] = "import"
        with patch.object(cn, "execute") as execute:
            for operation in (environment.create_cluster, environment.start_infra):
                with self.assertRaises(ValueError):
                    operation(target)
            execute.assert_not_called()

    def test_import_rejects_source_tampering_before_creating_target(self):
        source = self.root / "source"
        source.mkdir()
        (source / "identity").write_text("original")
        digest = sha(source / "identity")
        (source / "identity").write_text("tampered")
        write_json(source / "manifest.json", {"status": "READY", "modelMode": "offline", "files": {"identity": digest}})
        with self.assertRaises(ValueError):
            environment.prepare(self.args(mode="import", source_bundle=source))
        self.assertFalse((self.root / "candidate").exists())

    def test_native_import_preserves_signing_rings_passwords_and_both_trust_anchors(self):
        source = self.root / "source"
        source_args = SimpleNamespace(project="capyiot-ms-source-fixture", bundle=source,
            mode="fresh", volume_prefix="capyiot-ms-source-fixture", model_mode="offline",
            source_project=None, legacy_env=None, legacy_tls=None, import_keys=None)
        # Actual native initialization and crypto formats, with only one initial TLS leaf.
        with patch.object(ms_init, "APPS", ()), contextlib.redirect_stdout(io.StringIO()):
            ms_init.initialize(source_args)
        for app in ms_init.APPS:
            ms_init.jwk_ring(source / "keys" / (app + ".json"))
        manifest = json.loads((source / "manifest.json").read_text())
        manifest["files"] = cn.material_inventory(source)
        write_json(source / "manifest.json", manifest)
        before = {relative: sha(source / relative) for relative in manifest["files"]}
        with patch.object(cn, "execute") as execute:
            environment.prepare(self.args(mode="import", source_bundle=source))
            execute.assert_not_called()
        candidate = self.root / "candidate"
        self.target(candidate)
        for relative, digest in before.items():
            self.assertEqual(sha(source / relative), digest)
            if relative.startswith(("keys/", "secrets/")) or relative in {
                "pki/ca.key", "pki/ca.pem", "pki/edge/ca.pem", "pki/edge/server.pem", "pki/edge/server.key"}:
                self.assertEqual(sha(candidate / relative), digest)

    def test_process_environment_cannot_change_selected_compose_target(self):
        result = SimpleNamespace(returncode=0, stdout=b"", stderr=b"")
        with patch.dict(os.environ, {"CN_BUNDLE": "secret-other-target", "COMPOSE_FILE": "other.yml", "MS_WRITERS_ENABLED": "true"}), \
                patch.object(cn.subprocess, "run", return_value=result) as process:
            cn.execute(["docker", "compose", "version"], env_values={"CN_BUNDLE": "selected-target"})
        passed = process.call_args.kwargs["env"]
        self.assertEqual(passed["CN_BUNDLE"], "selected-target")
        self.assertNotIn("COMPOSE_FILE", passed)
        self.assertNotIn("MS_WRITERS_ENABLED", passed)

    def test_subprocess_errors_do_not_echo_secret_diagnostics(self):
        result = SimpleNamespace(returncode=1, stdout=b"private-key", stderr=b"DEEPSEEK_API_KEY=private-value")
        with patch.object(cn.subprocess, "run", return_value=result):
            with self.assertRaises(RuntimeError) as error:
                cn.execute(["docker", "inspect", "selected-target"])
        self.assertNotIn("private", str(error.exception))

    def test_tls_namespace_sans_are_added_without_changing_compose_default(self):
        from cryptography import x509

        # Limit this fixture to one leaf while using the actual CA/PKCS12 implementation.
        for namespace in (None, cn.NAMESPACE):
            bundle = self.root / (namespace or "compose")
            with patch.object(ms_init, "APPS", ()):
                ms_init.provision_tls(bundle, "local-test-store-password", namespace=namespace)
            cert = x509.load_pem_x509_certificate((bundle / "pki/edge/server.pem").read_bytes())
            names = cert.extensions.get_extension_for_class(x509.SubjectAlternativeName).value.get_values_for_type(x509.DNSName)
            self.assertIn("emqx", names)
            self.assertEqual("emqx." + cn.NAMESPACE + ".svc.cluster.local" in names, namespace is not None)
            original = (bundle / "pki/ca.pem").read_bytes()
            with patch.object(ms_init, "APPS", ()):
                ms_init.provision_tls(bundle, "local-test-store-password", namespace=namespace)
            self.assertEqual((bundle / "pki/ca.pem").read_bytes(), original)


if __name__ == "__main__":
    unittest.main()
