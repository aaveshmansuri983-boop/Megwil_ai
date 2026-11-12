import json
import queue
import socket
import sys
import tempfile
from unittest.mock import patch
import threading
import unittest
from pathlib import Path

RUNTIME = Path(__file__).parents[1]
if str(RUNTIME) not in sys.path:
    sys.path.insert(0, str(RUNTIME))

from android_daemon import AndroidGuiSocketClient
from android_actions import parse_voice_command
from llm.bootstrap import configure_provider


class FakeGuiSocket:
    def __init__(self, path):
        self.path = str(path)
        self.received = queue.Queue()
        self.server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.server.bind(self.path)
        self.server.listen(1)
        self.stop_event = threading.Event()
        self.thread = threading.Thread(target=self._serve, daemon=True)
        self.thread.start()

    def _serve(self):
        connection = None
        try:
            connection, _ = self.server.accept()
            connection.sendall(b'{"type":"state","state":"idle"}\n')
            buffer = bytearray()
            while not self.stop_event.is_set():
                chunk = connection.recv(4096)
                if not chunk:
                    break
                buffer.extend(chunk)
                while b"\n" in buffer:
                    index = buffer.index(b"\n")
                    line = bytes(buffer[:index])
                    del buffer[: index + 1]
                    message = json.loads(line.decode("utf-8"))
                    if message.get("type") == "ping":
                        connection.sendall(b'{"type":"pong"}\n')
                    else:
                        self.received.put(message)
        except OSError:
            pass
        finally:
            if connection is not None:
                connection.close()

    def close(self):
        self.stop_event.set()
        try:
            self.server.close()
        except OSError:
            pass
        self.thread.join(timeout=1.0)


class AndroidBridgeTests(unittest.TestCase):
    def test_real_gui_socket_handshake_and_message_forwarding(self):
        with tempfile.TemporaryDirectory() as tmp:
            server = FakeGuiSocket(Path(tmp) / "jarvis.sock")
            client = AndroidGuiSocketClient(server.path)
            try:
                status = client.wait_connected(4.0)
                self.assertTrue(status["connected"], status)
                self.assertEqual(status["runtime_state"], "idle")
                self.assertTrue(client.send_message("open WhatsApp"))
                self.assertTrue(client.send_control("start_listening"))
                first = server.received.get(timeout=2.0)
                second = server.received.get(timeout=2.0)
                self.assertEqual(first["type"], "hello")
                self.assertEqual(second["type"], "message")
                self.assertEqual(second["content"], "open WhatsApp")
            finally:
                client.stop()
                server.close()

    def test_android_bridge_uses_acl_command_names(self):
        bridge = (RUNTIME / "android-host" / "voice-bridge.js").read_text()
        expected = (
            "get_daemon_status",
            "get_runtime_diagnostics",
            "start_backend",
            "listen_once",
            "send_daemon_message",
            "send_daemon_control",
            "perform_phone_action",
        )
        for command in expected:
            self.assertIn("plugin:jarvis-voice|" + command, bridge)
        for stale in ("getDaemonStatus", "startBackend", "listenOnce", "sendDaemonMessage", "sendDaemonControl", "performPhoneAction"):
            self.assertNotIn("plugin:jarvis-voice|" + stale, bridge)
        plugin = (RUNTIME / "android-host" / "JarvisVoicePlugin.kt").read_text()
        permissions = (RUNTIME / "configure_chaquopy.py").read_text()
        self.assertIn("fun saveOnlineProvider", plugin)
        self.assertIn('commands.allow = ["save_online_provider"]', permissions)
        self.assertIn('"allow-save-online-provider"', permissions)
        self.assertIn("SAVE ONLINE PROVIDER", bridge)

    def test_generated_android_config_carries_voice_capability(self):
        config_script = (RUNTIME / "configure_chaquopy.py").read_text()
        self.assertIn('security_config["capabilities"] = configured_capabilities', config_script)
        self.assertIn('generated_config_path = android / "app" / "src" / "main" / "assets" / "tauri.conf.json"', config_script)
        self.assertIn('generated_config_path.write_text(serialized_config)', config_script)
        self.assertIn('if "jarvis-android-voice" not in packaged_capabilities:', config_script)

    def test_exact_voice_action_phrases(self):
        self.assertEqual(parse_voice_command("open WhatsApp"), {"action": "open_app", "target": "whatsapp"})
        self.assertEqual(parse_voice_command("call +91 98765 43210"), {"action": "dial", "target": "+919876543210"})
        self.assertEqual(
            parse_voice_command("send WhatsApp message to 9876543210 saying hello"),
            {"action": "whatsapp", "target": "9876543210", "value": "hello"},
        )
        self.assertEqual(parse_voice_command("turn on flashlight"), {"action": "flashlight", "value": "on"})
        self.assertEqual(parse_voice_command("set volume to 55%"), {"action": "volume", "value": "55"})
        self.assertIsNone(parse_voice_command("call me when you arrive"))


    def test_hybrid_provider_pool_uses_cloud_first_and_local_fallback(self):
        with tempfile.TemporaryDirectory() as tmp:
            config = Path(tmp)
            (config / "providers.json").write_text(json.dumps({
                "providers": [
                    {"name": "android-online-groq", "type": "api",
                     "url": "https://api.groq.com/openai/v1", "model": "llama-3.3-70b-versatile",
                     "api_key": "test-secret"},
                    {"name": "android-local-llama", "type": "api",
                     "url": "http://127.0.0.1:8080/v1", "model": "local-old"}
                ]
            }))
            with patch.dict("os.environ", {"JARVIS_CONFIG_DIR": str(config)}):
                configure_provider()
            providers = json.loads((config / "providers.json").read_text())["providers"]
            self.assertEqual(providers[0]["name"], "android-online-groq")
            self.assertEqual(providers[-1]["name"], "android-local-llama")
            self.assertEqual(providers[-1]["model"], "local-qwen2.5-0.5b")
            self.assertEqual(providers[0]["api_key"], "test-secret")

    def test_hybrid_provider_pool_is_offline_only_without_cloud_keys(self):
        with tempfile.TemporaryDirectory() as tmp:
            config = Path(tmp)
            with patch.dict("os.environ", {"JARVIS_CONFIG_DIR": str(config)}):
                configure_provider()
            providers = json.loads((config / "providers.json").read_text())["providers"]
            self.assertEqual(len(providers), 1)
            self.assertEqual(providers[0]["name"], "android-local-llama")
            self.assertEqual(providers[0]["url"], "http://127.0.0.1:8080/v1")



if __name__ == "__main__":
    unittest.main()
