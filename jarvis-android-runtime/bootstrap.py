"""Fail-closed preflight/launcher for an embedded Project-JARVIS Android runtime.

This deliberately refuses to report a healthy backend until required sidecars
and Python packages are present. It does not substitute the demo IPC server.
"""
from __future__ import annotations

import importlib.util
import os
import shutil
import sys
from pathlib import Path


def configure_private_storage() -> Path:
    raw_root = os.environ.get("JARVIS_ANDROID_DATA_DIR") or os.environ.get("HOME")
    if not raw_root:
        raise RuntimeError("Android host must provide JARVIS_ANDROID_DATA_DIR")
    root = Path(raw_root).expanduser().resolve()
    root.mkdir(parents=True, exist_ok=True)
    for name in ("config", "data", "models", "bin", "logs"):
        path = root / name
        path.mkdir(parents=True, exist_ok=True)
        try:
            path.chmod(0o700)
        except OSError:
            pass
    os.environ["JARVIS_ANDROID_DATA_DIR"] = str(root)
    os.environ["JARVIS_CONFIG_DIR"] = str(root / "config")
    os.environ["JARVIS_DATA_DIR"] = str(root / "data")
    os.environ["JARVIS_MODELS_DIR"] = str(root / "models")
    os.environ["XDG_CONFIG_HOME"] = str(root / "config")
    os.environ["XDG_DATA_HOME"] = str(root / "data")
    os.environ["JARVIS_GUI_SOCKET"] = str(root / "data" / "jarvis.sock")
    os.environ["JARVIS_INPUT_SOCKET"] = str(root / "data" / "input.sock")
    os.environ["JARVIS_OUTPUT_SOCKET"] = str(root / "data" / "output.sock")
    return root


def inspect_runtime() -> dict[str, object]:
    root = configure_private_storage()
    required_modules = ("ollama", "dotenv", "mcp", "httpx", "pathspec")
    required_bins = ("dispatch", "dmcp", "contextor")
    modules = {name: importlib.util.find_spec(name) is not None for name in required_modules}
    bins = {name: shutil.which(name) is not None or (root / "bin" / name).is_file()
            for name in required_bins}
    return {
        "platform": sys.platform,
        "android_api": getattr(sys, "getandroidapilevel", lambda: None)(),
        "data_dir": str(root),
        "modules": modules,
        "sidecars": bins,
        "ready": all(modules.values()) and all(bins.values()),
        "voice_note": "Android voice requires the native AudioRecord/TextToSpeech host bridge; sounddevice is not assumed to work.",
    }


def main() -> int:
    import json
    report = inspect_runtime()
    print(json.dumps(report, indent=2))
    if not report["ready"]:
        print("JARVIS Android backend is NOT ready; refusing to start a partial daemon.")
        return 2
    from jarvis.main import main as jarvis_main
    jarvis_main()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
