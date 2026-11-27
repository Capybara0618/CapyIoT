"""Mutation cases for the source guard; executed only with unified deployment tests."""

import shutil
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_microservices_boundaries import ROOT, review  # noqa: E402


class SourceBoundaryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=ROOT / "var")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        # Copy only deployment-relevant source; never build output or private local files.
        for path in (ROOT / "backend").rglob("*"):
            relative = path.relative_to(ROOT)
            if (
                path.is_file()
                and "target" not in path.parts
                and ("/src/main/" in path.as_posix() or path.name == "pom.xml")
            ):
                target = self.root / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(path, target)
        shutil.copyfile(
            ROOT / "compose.microservices.yml", self.root / "compose.microservices.yml"
        )

    def test_old_audience_cannot_return_through_a_runtime_decoder(self):
        path = (
            self.root
            / "backend/workflow-service/src/main/java/io/capyiot/workflow/work/WorkJwtDecoder.java"
        )
        path.write_text(
            path.read_text(encoding="utf-8")
            + "\n// accidentally re-enabled capyiot-core\n",
            encoding="utf-8",
        )
        self.assertTrue(
            any(
                "retired runtime dependency capyiot-core" in issue
                for issue in review(self.root)["issues"]
            )
        )

    def test_a_domain_cannot_import_another_business_artifact(self):
        path = self.root / "backend/device-service/pom.xml"
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "<dependencies>",
                "<dependencies><dependency><groupId>io.capyiot</groupId><artifactId>workflow-service</artifactId></dependency>",
                1,
            ),
            encoding="utf-8",
        )
        self.assertTrue(
            any(
                "forbidden implementation dependency workflow-service" in issue
                for issue in review(self.root)["issues"]
            )
        )

    def test_stable_consumer_group_cannot_be_renamed_for_a_fresh_replay(self):
        path = (
            self.root
            / "backend/device-service/src/main/java/io/capyiot/device/video/DeviceStateRuntime.java"
        )
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "core-device-state-v1", "new-consumer-start-earliest"
            ),
            encoding="utf-8",
        )
        self.assertTrue(
            any(
                "Logical consumer identities" in issue
                for issue in review(self.root)["issues"]
            )
        )


if __name__ == "__main__":
    unittest.main()
