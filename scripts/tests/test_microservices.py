"""Deployment boundary tests; maintained here and deferred to unified verification."""

import json
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import microservices_common as common  # noqa: E402
import microservices_control as control  # noqa: E402


class DeploymentBoundaryTest(unittest.TestCase):
    def test_inventory_batches_probes_but_rejects_missing_checksums(self):
        names = [schema + ".fixture" for schema in common.SCHEMAS]
        tables = "".join(schema + "\tfixture\n" for schema in common.SCHEMAS)
        counts = "".join(full + "\t2\n" for full in names)
        checksums = "".join(full + "\t123\n" for full in names)
        with patch.object(
            control, "db", side_effect=[tables, counts, checksums, ""]
        ) as query:
            result = control.inventory("fixture-mysql")
        self.assertEqual(result["tables"], dict.fromkeys(names, 2))
        self.assertEqual(result["checksums"], dict.fromkeys(names, "123"))
        self.assertEqual(query.call_count, 4)
        with patch.object(
            control,
            "db",
            side_effect=[tables, counts, checksums.replace("123", "NULL", 1)],
        ):
            with self.assertRaises(ValueError):
                control.inventory("fixture-mysql")
        with patch.object(
            control,
            "db",
            side_effect=[tables, counts, checksums.splitlines()[0] + "\n"],
        ):
            with self.assertRaises(ValueError):
                control.inventory("fixture-mysql")

    def setUp(self):
        self.folder = tempfile.TemporaryDirectory(dir=common.ROOT / "var")
        self.root = Path(self.folder.name)
        self.addCleanup(self.folder.cleanup)

    def bundle(self, **changes):
        bundle = self.root / "bundle"
        bundle.mkdir()
        values = {
            "MS_PROJECT": "capyiot-ms-verify-fixture",
            "MS_BUNDLE": bundle.as_posix(),
            "MS_VOLUME_PREFIX": "capyiot-ms-verify-fixture",
            "MS_MODEL_MODE": "offline",
            "MS_WRITERS_ENABLED": "false",
        }
        values.update(changes)
        env = bundle / "deployment.env"
        env.write_text("\n".join(k + "=" + v for k, v in values.items()))
        common.write_json(
            bundle / "manifest.json",
            {
                "status": "READY",
                "project": values["MS_PROJECT"],
                "mode": "fresh",
                "sourceProject": None,
                "volumePrefix": "capyiot-ms-verify-fixture",
                "modelMode": "offline",
                "files": {},
            },
        )
        return bundle, env

    def test_unified_verifier_rejects_an_experience_project_before_reading_any_secret(
        self,
    ):
        with self.assertRaises(ValueError):
            common.Target(
                "capyiot",
                common.ROOT / "compose.microservices.yml",
                self.root / "missing.env",
                verification=True,
            )

    def test_shell_writer_and_project_overrides_do_not_bypass_explicit_passive_target(
        self,
    ):
        _, env = self.bundle()
        command = [
            "docker",
            "compose",
            "--env-file",
            str(env),
            "-p",
            "capyiot-ms-verify-fixture",
            "up",
        ]
        inherited = {
            "MS_WRITERS_ENABLED": "true",
            "MS_BUNDLE": "elsewhere",
            "COMPOSE_PROJECT_NAME": "capyiot",
            "COMPOSE_FILE": "compose.yml",
            "DEEPSEEK_API_KEY": "never-pass-to-offline-checks",
        }
        selected = common.command_env(command, inherited)
        self.assertEqual(selected["MS_WRITERS_ENABLED"], "false")
        self.assertNotIn("COMPOSE_PROJECT_NAME", selected)
        self.assertNotIn("COMPOSE_FILE", selected)
        self.assertNotIn("DEEPSEEK_API_KEY", selected)

    def test_changed_volume_identity_is_rejected(self):
        _, env = self.bundle(MS_VOLUME_PREFIX="original-experience")
        with self.assertRaises(ValueError):
            common.Target(
                "capyiot-ms-verify-fixture",
                common.ROOT / "compose.microservices.yml",
                env,
                verification=True,
            )

    def test_modified_credentials_fail_preflight_without_a_docker_call(self):
        bundle, env = self.bundle()
        secret = bundle / "key.json"
        secret.write_text("first")
        path = bundle / "manifest.json"
        manifest = json.loads(path.read_text())
        manifest["files"] = {"key.json": common.sha(secret)}
        common.write_json(path, manifest)
        secret.write_text("replacement")
        target = common.Target(
            "capyiot-ms-verify-fixture",
            common.ROOT / "compose.microservices.yml",
            env,
            verification=True,
        )
        with patch.object(common, "run") as docker:
            with self.assertRaises(ValueError):
                target.preflight()
            docker.assert_not_called()

    def test_activation_override_cannot_replace_the_selected_volume_or_bundle(self):
        bundle, env = self.bundle()
        (bundle / "active.env").write_text(
            "MS_WRITERS_ENABLED=true\nMS_VOLUME_PREFIX=capyiot\n"
        )
        target = common.Target(
            "capyiot-ms-verify-fixture",
            common.ROOT / "compose.microservices.yml",
            env,
            verification=True,
        )
        with self.assertRaises(ValueError):
            target.compose("up", active=True)

    def test_unknown_running_application_is_not_assumed_to_be_read_only(self):
        with patch.object(
            control,
            "containers",
            return_value=[{"service": "unknown-writer", "running": True}],
        ):
            with self.assertRaises(ValueError):
                control.stopped("source-project")

    def test_automatic_rollback_after_possible_new_writes_never_restores_or_starts_source(
        self,
    ):
        bundle, env = self.bundle()
        reports = self.root / "reports"
        reports.mkdir()
        common.write_json(
            reports / "rollout.json",
            {
                "project": "capyiot-ms-verify-fixture",
                "bundle": str(bundle),
                "phase": "ACTIVE_FROZEN",
                "everActivated": True,
            },
        )
        target = common.Target(
            "capyiot-ms-verify-fixture",
            common.ROOT / "compose.microservices.yml",
            env,
            verification=True,
        )
        args = SimpleNamespace(
            project=target.project,
            compose_file=target.compose_file,
            env_file=env,
            report_dir=reports,
            action="rollback",
            execute=True,
            restore_proof=None,
        )
        with (
            patch.object(control, "Target", return_value=target),
            patch.object(control, "run") as docker,
            patch.object(control, "restore_sql") as restore,
        ):
            with self.assertRaises(ValueError):
                control.operate(args)
            docker.assert_not_called()
            restore.assert_not_called()

    def test_changed_backup_is_rejected_before_destructive_restore(self):
        folder = self.root / "backup"
        folder.mkdir()
        dump = folder / "five-databases.sql"
        dump.write_text("original")
        common.write_json(
            folder / "backup.json", {"files": {"five-databases.sql": common.sha(dump)}}
        )
        dump.write_text("changed")
        with self.assertRaises(ValueError):
            control.validate_backup(folder)


if __name__ == "__main__":
    unittest.main()
