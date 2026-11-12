"""Persistent Android client for Project-JARVIS' private GUI AF_UNIX socket."""
from __future__ import annotations
import json
import os
import select
import socket
import threading
import time
from pathlib import Path
from typing import Any


class AndroidGuiSocketClient:
    def __init__(self, path: str | None = None):
        root = os.environ.get("JARVIS_ANDROID_DATA_DIR") or os.environ.get("HOME") or ""
        fallback = str(Path(root) / "data" / "jarvis.sock") if root else ""
        self.path = path or os.environ.get("JARVIS_GUI_SOCKET") or fallback
        self._stop = threading.Event()
        self._connected = threading.Event()
        self._lock = threading.RLock()
        self._send_lock = threading.Lock()
        self._socket: socket.socket | None = None
        self._thread: threading.Thread | None = None
        self._status: dict[str, Any] = {
            "state": "offline", "connected": False, "runtime_state": "unknown",
            "last_error": None, "last_event": None,
        }

    def start(self) -> bool:
        with self._lock:
            if self._thread is not None and self._thread.is_alive():
                return True
            self._stop.clear()
            self._thread = threading.Thread(target=self._run, name="Jarvis-Android-GUI-IPC", daemon=True)
            self._thread.start()
        return True

    def wait_connected(self, timeout: float = 0.0) -> dict[str, Any]:
        self.start()
        self._connected.wait(max(0.0, float(timeout)))
        return self.status()

    def status(self) -> dict[str, Any]:
        with self._lock:
            return dict(self._status)

    def status_json(self) -> str:
        return json.dumps(self.status(), ensure_ascii=False)

    def send_message(self, text: str) -> bool:
        content = str(text or "").strip()
        if not content or len(content) > 4000:
            return False
        return self._send_payload({"type": "message", "content": content})

    def send_control(self, command: str) -> bool:
        allowed = {"start_listening", "stop_listening", "shutdown_request"}
        command = str(command or "").strip()
        if command not in allowed:
            return False
        return self._send_payload({"type": command})

    def stop(self) -> None:
        self._stop.set()
        with self._lock:
            sock = self._socket
            self._socket = None
        if sock is not None:
            try:
                sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            try:
                sock.close()
            except OSError:
                pass
        thread = self._thread
        if thread is not None and thread.is_alive():
            thread.join(timeout=1.0)
        self._set_status("offline", last_error=None)

    def _set_status(self, state: str, **fields: Any) -> None:
        with self._lock:
            self._status["state"] = state
            self._status["connected"] = state == "connected"
            self._status.update(fields)
        if state == "connected":
            self._connected.set()
        else:
            self._connected.clear()

    def _send(self, sock: socket.socket, payload: dict[str, Any]) -> None:
        data = (json.dumps(payload, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8")
        with self._send_lock:
            sock.sendall(data)

    def _read_line(self, sock: socket.socket, buffer: bytearray, timeout: float) -> bytes | None:
        deadline = time.monotonic() + timeout
        while not self._stop.is_set():
            marker = buffer.find(b"\n")
            if marker >= 0:
                line = bytes(buffer[:marker])
                del buffer[: marker + 1]
                return line
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                return None
            readable, _, _ = select.select([sock], [], [], min(remaining, 0.25))
            if not readable:
                continue
            chunk = sock.recv(4096)
            if not chunk:
                raise ConnectionError("JARVIS GUI socket closed the connection")
            buffer.extend(chunk)
        return None

    def _connect_and_handshake(self) -> tuple[socket.socket, bytearray, dict[str, Any]]:
        if not self.path:
            raise RuntimeError("JARVIS_GUI_SOCKET path is not configured")
        sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        try:
            sock.settimeout(5.0)
            sock.connect(self.path)
            sock.settimeout(None)
            buffer = bytearray()
            first = self._read_line(sock, buffer, 5.0)
            if first is None:
                raise TimeoutError("JARVIS GUI socket did not send its initial state")
            initial = json.loads(first.decode("utf-8"))
            if not isinstance(initial, dict) or initial.get("type") != "state":
                raise RuntimeError("JARVIS GUI socket returned an invalid initial state")
            self._send(sock, {"type": "hello", "label": "JARVIS Android HUD", "subscribe": ["activity", "action", "user_message"]})
            self._send(sock, {"type": "ping"})
            deadline = time.monotonic() + 5.0
            while not self._stop.is_set():
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise TimeoutError("JARVIS GUI socket did not complete ping/pong handshake")
                line = self._read_line(sock, buffer, remaining)
                if line is None:
                    continue
                message = json.loads(line.decode("utf-8"))
                if isinstance(message, dict) and message.get("type") == "pong":
                    return sock, buffer, initial
            raise RuntimeError("JARVIS GUI socket connection was stopped")
        except Exception:
            sock.close()
            raise

    def _send_payload(self, payload: dict[str, Any]) -> bool:
        with self._lock:
            sock = self._socket
            connected = bool(self._status.get("connected"))
        if sock is None or not connected:
            return False
        try:
            self._send(sock, payload)
            return True
        except OSError as error:
            self._set_status("offline", last_error=str(error))
            return False

    def _run(self) -> None:
        backoff = 1.0
        while not self._stop.is_set():
            sock: socket.socket | None = None
            try:
                self._set_status("connecting", last_error=None)
                sock, buffer, initial = self._connect_and_handshake()
                with self._lock:
                    self._socket = sock
                self._set_status("connected", runtime_state=str(initial.get("state") or "idle"), last_error=None)
                backoff = 1.0
                while not self._stop.is_set():
                    line = self._read_line(sock, buffer, 1.0)
                    if line is None:
                        continue
                    try:
                        message = json.loads(line.decode("utf-8"))
                    except (UnicodeDecodeError, json.JSONDecodeError):
                        continue
                    if not isinstance(message, dict):
                        continue
                    kind = str(message.get("type") or "")
                    if kind == "ping":
                        self._send(sock, {"type": "pong"})
                    elif kind == "state":
                        self._set_status("connected", runtime_state=str(message.get("state") or "unknown"), last_error=None)
                    elif kind:
                        self._set_status("connected", last_event=kind)
            except Exception as error:
                if not self._stop.is_set():
                    self._set_status("offline", last_error=f"{type(error).__name__}: {error}")
            finally:
                with self._lock:
                    if self._socket is sock:
                        self._socket = None
                if sock is not None:
                    try:
                        sock.close()
                    except OSError:
                        pass
                self._connected.clear()
            if not self._stop.is_set():
                self._stop.wait(backoff)
                backoff = min(backoff * 2.0, 8.0)


_client = AndroidGuiSocketClient()
def start() -> bool: return _client.start()
def wait_connected(timeout: float = 0.0) -> dict[str, Any]: return _client.wait_connected(timeout)
def status() -> dict[str, Any]: return _client.status()
def status_json() -> str: return _client.status_json()
def send_message(text: str) -> bool: return _client.send_message(text)
def send_control(command: str) -> bool: return _client.send_control(command)
def stop() -> None: _client.stop()
