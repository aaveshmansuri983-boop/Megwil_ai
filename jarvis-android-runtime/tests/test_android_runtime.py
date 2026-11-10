import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest import mock


SPEC = importlib.util.spec_from_file_location(
    "jarvis_android_bootstrap",
    Path(__file__).parents[1] / "bootstrap.py",
)
bootstrap = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(bootstrap)


RUNTIME_DIR = Path(__file__).parents[1]
if str(RUNTIME_DIR) not in __import__("sys").path:
    __import__("sys").path.insert(0, str(RUNTIME_DIR))
import android_audio
import android_sounddevice


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

    def test_native_pcm_bridge_and_stream_reference_counting(self):
        received = []

        class FakeAudioRouter:
            def __init__(self):
                self.starts = 0
                self.stops = 0

            def startRecording(self):
                self.starts += 1
                return True

            def stopRecording(self):
                self.stops += 1

        router = FakeAudioRouter()
        first = android_sounddevice.InputStream(
            callback=lambda data, frames, _time, _status: received.append(
                (data.tobytes(), frames)
            )
        )
        second = android_sounddevice.InputStream(
            callback=lambda data, frames, _time, _status: None
        )
        with mock.patch.object(android_sounddevice, "_audio_router", router):
            first.start()
            second.start()
            android_audio.feed(b"\x01\x00" * 4)
            self.assertEqual(router.starts, 1)
            self.assertEqual(received, [(b"\x01\x00" * 4, 4)])
            first.stop()
            self.assertEqual(router.stops, 0)
            second.stop()
            self.assertEqual(router.stops, 1)

    def test_native_speech_text_bridge_delivers_final_result(self):
        received = []
        callback = lambda text, final: received.append((text, final))
        android_audio.subscribe_text(callback)
        try:
            android_audio.feed_text("Jarvis, hello", True)
        finally:
            android_audio.unsubscribe_text(callback)
        self.assertEqual(received, [("Jarvis, hello", True)])

    def test_missing_parent_package_is_reported_not_raised(self):
        with mock.patch.object(
            bootstrap.importlib.util, "find_spec", side_effect=ModuleNotFoundError("jarvis")
        ):
            self.assertFalse(bootstrap._module_available("jarvis.voice.stt.android_stt"))

    def test_preflight_fails_closed_without_all_runtime_components(self):
        with tempfile.TemporaryDirectory() as tmp:
            os.environ["JARVIS_ANDROID_DATA_DIR"] = tmp
            report = bootstrap.inspect_runtime()
            self.assertIn("modules", report)
            self.assertIn("sidecars", report)
            self.assertIsInstance(report["ready"], bool)


if __name__ == "__main__":
    unittest.main()
