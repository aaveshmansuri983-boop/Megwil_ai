"""Android platform adapter for the Project-JARVIS Python runtime.

Android app processes run under an isolated UID and private data directory.
IPC remains AF_UNIX so the existing Rust/Tauri GUI client can use the same
newline-delimited JSON GUI protocol without changing desktop behavior.
"""
from __future__ import annotations

import os
from pathlib import Path
from typing import Optional

from .linux import LinuxPlatform


class AndroidPlatform(LinuxPlatform):
    """Android app sandbox adapter; Linux IPC semantics, Android-safe services."""

    def _private_root(self) -> Path:
        root = os.environ.get("JARVIS_ANDROID_DATA_DIR") or os.environ.get("HOME")
        if not root:
            raise RuntimeError("JARVIS_ANDROID_DATA_DIR/HOME is not set")
        path = Path(root).expanduser().resolve()
        path.mkdir(parents=True, exist_ok=True)
        try:
            path.chmod(0o700)
        except OSError:
            pass
        return path

    def config_dir(self) -> Path:
        return self._private_root() / "config"

    def data_dir(self) -> Path:
        return self._private_root() / "data"

    def sidecar_search_dirs(self) -> list[Path]:
        root = self._private_root()
        return [root / "bin", root / "lib" / "jarvis" / "bin"]

    def _detect_desktop_notifications(self) -> bool:
        # Android notifications must be posted by the host Android service.
        return False

    async def send_desktop_notification(
        self, title: str, body: str, timeout_ms: int
    ) -> Optional[str]:
        return None

    def try_start_service(self, name: str, base_url: str) -> bool:
        # Never assume systemd/launchd or start an unmanaged daemon on Android.
        return False

    def privileged_prefixes(self) -> tuple[str, ...]:
        return ()

    def askpass_helpers(self) -> tuple[str, ...]:
        return ()

    def elevate(self, command: str) -> str:
        raise RuntimeError("Privilege elevation is not available in Android app sandbox")

    def grant_privilege(self) -> bool:
        return False

    def revoke_privilege(self) -> bool:
        return False

    def is_privilege_granted(self) -> bool:
        return False

    def open_command(self, target: str) -> list[str]:
        # The Android host should handle intents; this is a safe diagnostic fallback.
        return ["am", "start", "-a", "android.intent.action.VIEW", "-d", target]
