import sys
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import cloud_native_common as cn
import cloud_native_release as release


class ReleaseBoundaries(unittest.TestCase):
    def test_release_waits_for_each_application_before_next_surge(self):
        target = SimpleNamespace(kubectl=lambda *args: list(args))
        events = []
        items = [{"kind": "ConfigMap"}, *[{"kind": "Deployment", "metadata": {"name": n}} for n in ("gateway", "platform-service")]]
        with patch.object(release, "apply", side_effect=lambda t, o: events.append(("apply", o.get("metadata", {}).get("name", "configs")))), patch.object(cn, "execute", side_effect=lambda cmd: events.append(("wait", cmd[2]))):
            release.apply_applications(target, items)
        self.assertEqual(events, [("apply", "configs"), ("apply", "gateway"), ("wait", "deployment/gateway"),
                                  ("apply", "platform-service"), ("wait", "deployment/platform-service")])

    def test_offline_fixture_reuses_release_runtime_without_credentials(self):
        from verify_cloud_native import offline_provider
        target = SimpleNamespace(manifest={"modelMode": "offline"})
        with patch.object(release, "apply") as apply, patch.object(cn, "execute") as execute:
            offline_provider(target, "capyiot/agent-service:cn-1234567")
            execute.assert_not_called()
        objects = apply.call_args.args[1]["items"]
        pod = next(o for o in objects if o["kind"] == "Deployment")["spec"]["template"]["spec"]
        self.assertEqual(pod["containers"][0]["image"], "capyiot/agent-service:cn-1234567")
        self.assertFalse(pod["automountServiceAccountToken"])
        self.assertFalse(any("secret" in v for v in pod["volumes"]))
        self.assertIn("model_fixture.py", objects[0]["data"])

    def test_jobs_only_mount_owned_database_credential(self):
        target = SimpleNamespace(bundle=Path("unread"))
        candidate = {"releaseId": "r123", "images": {"device-service": "capyiot/device-service:cn-1234567"}}
        with patch.object(cn, "safe_file", return_value=SimpleNamespace(read_bytes=lambda: b"local-fixture")) as source:
            secret, job = release.migration_job(target,candidate,"device-service")
        self.assertEqual(set(secret["data"]), {"db-password"})
        self.assertEqual(source.call_args.args[1], "secrets/device-db-password")
        self.assertEqual(job["spec"]["backoffLimit"], 0)
        self.assertFalse(job["spec"]["template"]["spec"]["automountServiceAccountToken"])

    def test_failed_or_uncertain_migration_cannot_be_replayed_as_new(self):
        with patch.object(cn, "execute") as execute:
            for phase in ("MIGRATING", "FAILED", "ACTIVE"):
                with self.assertRaises(ValueError):
                    release.migrate(None,{"phase": phase})
            execute.assert_not_called()

    def test_activation_requires_passive_proof(self):
        with patch.object(cn, "execute") as execute:
            with self.assertRaises(ValueError):
                release.deploy(None,{"phase": "MIGRATED"},active=True)
            execute.assert_not_called()

    def test_no_previous_release_means_no_blind_undo(self):
        with patch.object(cn, "execute") as execute:
            with self.assertRaises(ValueError):
                release.rollback(None,{"previousReleaseId": None})
            execute.assert_not_called()
