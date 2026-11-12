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
    for name in ("config", "data", "models", "bin", "lib", "logs"):
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
    bin_dir, lib_dir = str(root / "bin"), str(root / "lib")
    os.environ["PATH"] = bin_dir + os.pathsep + os.environ.get("PATH", "")
    os.environ["LD_LIBRARY_PATH"] = lib_dir + (os.pathsep + os.environ["LD_LIBRARY_PATH"] if os.environ.get("LD_LIBRARY_PATH") else "")
    return root


def _module_available(name: str) -> bool:
    """Return False for absent nested packages instead of crashing preflight.

    find_spec('parent.child') imports the parent package; on a clean host test
    runner that parent may not be installed yet. Missing optional/runtime modules
    are readiness failures, not exceptions that should abort the diagnostic.
    """
    try:
        return importlib.util.find_spec(name) is not None
    except (ImportError, ModuleNotFoundError, ValueError):
        return False


def inspect_runtime() -> dict[str, object]:
    root = configure_private_storage()
    required_modules = ("ollama", "dotenv", "mcp", "httpx", "pathspec", "android_audio", "android_actions", "android_daemon", "android_sounddevice", "jarvis.voice.stt.android_stt", "jarvis.voice.activation.android_activation", "jarvis.voice.tts.android_tts")
    required_bins = ("dispatch", "dmcp", "contextor")
    required_llm_libs = ("libllama-server-impl.so", "libllama-common.so", "libmtmd.so", "libllama.so", "libggml.so", "libggml-cpu.so", "libggml-base.so")
    modules = {name: _module_available(name) for name in required_modules}
    bins = {name: shutil.which(name) is not None or (root / "bin" / name).is_file()
            for name in required_bins}
    llm = {"server": (root / "bin" / "llama-server").is_file(),
           "libraries": {name: (root / "lib" / name).is_file() for name in required_llm_libs},
           "model_present": any((root / "models").glob("*.gguf"))}
    android_python = callable(getattr(sys, "getandroidapilevel", None))
    voice_bridge = os.environ.get("JARVIS_ANDROID_VOICE_BRIDGE_READY") == "1"
    return {
        "platform": sys.platform,
        "android_api": getattr(sys, "getandroidapilevel", lambda: None)(),
        "data_dir": str(root),
        "android_python_runtime": android_python,
        "modules": modules,
        "sidecars": bins,
        "llm": llm,
        "native_voice_bridge_ready": voice_bridge,
        "ready": android_python and all(modules.values()) and all(bins.values()) and llm["server"] and all(llm["libraries"].values()) and voice_bridge,
        "voice_note": "Host must initialize native TextToSpeech and SpeechRecognizer, obtain RECORD_AUDIO permission, and set JARVIS_ANDROID_VOICE_BRIDGE_READY=1 only after successful initialization. AudioRecord PCM is exposed through android_audio.",
    }


def _write_backend_status(root: Path, state: str, **details: object) -> None:
    import json
    logs = root / "logs"
    logs.mkdir(parents=True, exist_ok=True)
    target = logs / "backend-status.json"
    temporary = target.with_suffix(".tmp")
    payload = {"state": state, "updated_at": __import__("time").time(), **details}
    temporary.write_text(json.dumps(payload, indent=2, default=str) + "\n")
    temporary.replace(target)


def main() -> int:
    import json
    report = inspect_runtime()
    root = Path(str(report["data_dir"]))
    _write_backend_status(root, "preflight", report=report)
    print(json.dumps(report, indent=2))
    if not report["ready"]:
        _write_backend_status(root, "not-ready", reason="Required Android runtime components are missing", report=report)
        print("JARVIS Android backend is NOT ready; refusing to start a partial daemon.")
        return 2
    try:
        _write_backend_status(root, "preparing-sidecars", report=report)
        from sidecar_launcher import prepare_sidecars
        prepare_sidecars(root)
        _write_backend_status(root, "starting-local-model", report=report)
        from llm.bootstrap import start_local_llm
        start_local_llm()
        if callable(getattr(sys, "getandroidapilevel", None)):
            os.environ["TTS_PROVIDER"] = "android"
            os.environ["STT_PROVIDER"] = "android"
            os.environ["ACTIVATION_PROVIDER"] = "android"
            os.environ["OUTPUT_MODE"] = "voice"
            import android_sounddevice
            sys.modules["sounddevice"] = android_sounddevice
        _write_backend_status(root, "starting-jarvis-daemon", report=report)
        from android_daemon import start as start_gui_socket_client, stop as stop_gui_socket_client
        start_gui_socket_client()
        try:
            from jarvis.main import main as jarvis_main
            jarvis_main()
        finally:
            stop_gui_socket_client()
        _write_backend_status(root, "stopped", report=report)
        return 0
    except Exception as error:
        _write_backend_status(root, "error", error_type=type(error).__name__, error=str(error), report=report)
        raise


if __name__ == "__main__":
    raise SystemExit(main())
