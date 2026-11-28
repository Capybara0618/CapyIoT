import json
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from verify_cloud_native import real_provider


class RealProviderBudgetTest(unittest.TestCase):
    def test_explicit_budget_is_applied_and_original_refs_restored_after_failure(self):
        commands, applied = [], []
        target = SimpleNamespace(kubectl=lambda *args: list(args))

        def execute(command):
            commands.append(command)
            if command[:2] == ["get", "deployment"]:
                return json.dumps({"spec": {"template": {"spec": {
                    "containers": [{"envFrom": [{"configMapRef": {"name": "original-config"}}]}],
                    "volumes": [{"name": "credentials", "secret": {"secretName": "original-secret"}}],
                }}}}).encode()
            if command[:2] == ["get", "secret"]:
                return b'{"data": {"old-key": "a2V5"}}'
            return b""

        with tempfile.TemporaryDirectory() as temporary:
            key = Path(temporary) / "model-key"
            key.write_text("fixture-only")
            with patch("verify_cloud_native.private_path", side_effect=Path), \
                 patch("verify_cloud_native.cn.safe_file", side_effect=lambda parent, name: parent / name), \
                 patch("verify_cloud_native.cn.execute", side_effect=execute), \
                 patch("cloud_native_manifests.configuration", return_value={}), \
                 patch("verify_cloud_native.release.resources", side_effect=lambda value: value), \
                 patch("verify_cloud_native.release.apply", side_effect=lambda _, value: applied.extend(value)):
                with self.assertRaisesRegex(RuntimeError, "scenario failed"):
                    with real_provider(target, key, "fixture", "https://model.invalid",
                                       requests_maximum=6, tools_maximum=6):
                        raise RuntimeError("scenario failed")
        config = next(value for value in applied if value["kind"] == "ConfigMap")["data"]
        self.assertEqual(config["AGENT_MAX_MODEL_REQUESTS"], "6")
        self.assertEqual(config["AGENT_MAX_TOOL_CALLS"], "6")
        patches = [json.loads(command[-1]) for command in commands if command[0] == "patch"]
        self.assertEqual(len(patches), 2)
        self.assertEqual([value["value"] for value in patches[-1]], ["original-config", "original-secret"])

    def test_invalid_caps_are_rejected_before_reading_credentials_or_contacting_cluster(self):
        with patch("verify_cloud_native.cn.execute") as execute, \
             patch("verify_cloud_native.cn.safe_file") as read:
            for requests, tools in [(7, 5), (2, 5), (True, 5), (6, 7), (6, 0), (6, 2.5)]:
                with self.subTest(requests=requests, tools=tools), self.assertRaises(ValueError):
                    with real_provider(None, "unused", "fixture", "https://model.invalid",
                                       requests_maximum=requests, tools_maximum=tools):
                        self.fail("Invalid budget accepted")
            execute.assert_not_called()
            read.assert_not_called()


if __name__ == "__main__":
    unittest.main()
