import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from cloud_native_frontend_artifact import inventory


class StaticArtifactBoundaries(unittest.TestCase):
    def test_missing_entry_or_javascript_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder)
            (root/"index.html").write_text("incomplete")
            with self.assertRaises(ValueError): inventory(root)

    def test_byte_changes_invalidate_artifact_identity(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);(root/"index.html").write_text("entry");(root/"app.js").write_text("version-one")
            before=inventory(root);(root/"app.js").write_text("version-two")
            self.assertNotEqual(before,inventory(root))
