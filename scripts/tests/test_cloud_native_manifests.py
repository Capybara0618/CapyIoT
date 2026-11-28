import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from cloud_native_manifests import application_objects, configuration, files_for


class ManifestBoundaries(unittest.TestCase):
    def test_offline_nine_tool_regression_and_real_model_have_separate_budgets(self):
        offline = configuration("agent-service")
        real = configuration("agent-service", model={"id": "real-model", "baseUrl": "https://provider.invalid"})
        self.assertEqual((offline["AGENT_MAX_MODEL_REQUESTS"], offline["AGENT_MAX_TOOL_CALLS"]), ("10", "10"))
        self.assertEqual((real["AGENT_MAX_MODEL_REQUESTS"], real["AGENT_MAX_TOOL_CALLS"]), ("3", "5"))

    def test_only_ingestion_identity_service_publishes_before_mqtt_ready(self):
        services = [x for x in application_objects(active=True) if x["kind"] == "Service"]
        published = [x["metadata"]["name"] for x in services if x["spec"].get("publishNotReadyAddresses")]
        self.assertEqual(published, ["device-ingestion"])

    def test_singletons_reject_multiple_replicas(self):
        for service in ("device-service", "alarm-service", "workflow-service", "device-ingestion", "agent-service"):
            with self.assertRaises(ValueError):
                application_objects(replicas={service: 2})

    def test_passive_writers_and_secret_ownership(self):
        for item in application_objects():
            if item["kind"] == "ConfigMap":
                for key, value in item["data"].items():
                    if key.endswith(("RUNTIME_ENABLED", "DISPATCH_ENABLED", "CONSUMPTION_ENABLED")):
                        self.assertEqual(value, "false")
        self.assertNotIn("db-password", files_for("gateway"))
        self.assertNotIn("db-password", files_for("device-ingestion"))
        self.assertEqual(files_for("agent-service")["db-password"], "secrets/agent-db-password")

    def test_probe_hits_local_pod_and_cluster_dns_is_not_docker_dns(self):
        resources = application_objects(active=True)
        frontend = next(x for x in resources if x["kind"] == "ConfigMap" and x["metadata"]["name"].startswith("frontend-"))
        self.assertNotEqual(frontend["data"]["NGINX_RESOLVER"], "127.0.0.11")
        self.assertTrue(frontend["data"]["NGINX_GATEWAY_HOST"].endswith(".svc.cluster.local"))
        for item in resources:
            if item["kind"] == "Deployment" and item["metadata"]["name"] != "frontend":
                container = item["spec"]["template"]["spec"]["containers"][0]
                self.assertIn("127.0.0.1", " ".join(container["readinessProbe"]["exec"]["command"]))
