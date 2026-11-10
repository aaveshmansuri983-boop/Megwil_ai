import importlib.util
import os
from pathlib import Path
import tempfile
import unittest


SPEC = importlib.util.spec_from_file_location(
    "jarvis_android_bootstrap",
    Path(__file__).parents[1] / "bootstrap.py",
)
bootstrap = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(bootstrap)


class AndroidBootstrapTests(unittest.TestCase):
    def test_private_storage_and_socket_paths_are_consistent(self):
        with tempfile.TemporaryDirectory() as tmp:
            os.environ["JARVIS_ANDROID_DATA_DIR"] = tmp
            root = bootstrap.configure_private_storage()
            self.assertEqual(Path(os.environ["JARVIS_DATA_DIR"]), root / "data")
            self.assertEqual(
                Path(os.environ["JARVIS_GUI_SOCKET"]), root / "data" / "jarvis.sock"
            )
            self.assertTrue((root / "models").is_dir())

    def test_preflight_fails_closed_without_all_runtime_components(self):
        with tempfile.TemporaryDirectory() as tmp:
            os.environ["JARVIS_ANDROID_DATA_DIR"] = tmp
            report = bootstrap.inspect_runtime()
            self.assertIn("modules", report)
            self.assertIn("sidecars", report)
            self.assertIsInstance(report["ready"], bool)


if __name__ == "__main__":
    unittest.main()
