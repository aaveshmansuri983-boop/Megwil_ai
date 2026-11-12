"""Start a private, on-device llama.cpp server for JARVIS.

The small starter model is downloaded once into app-private storage. No cloud
inference endpoint or API key is used. The backend talks to the local server
through JARVIS' existing OpenAI-compatible API provider.
"""
from __future__ import annotations

import hashlib
import os
import shutil
import subprocess
import time
import urllib.request
from pathlib import Path

MODEL_NAME = "Qwen2.5-0.5B-Instruct-Q4_K_M.gguf"
MODEL_URL = (
    "https://huggingface.co/bartowski/"
    "Qwen2.5-0.5B-Instruct-GGUF/resolve/d71b21e09fe053c278d73092a855b8a99684693a/"
    + MODEL_NAME
)
MODEL_PATH = Path(os.environ.get("JARVIS_MODELS_DIR", "")) / MODEL_NAME
MODEL_SHA256 = "6eb923e7d26e9cea28811e1a8e852009b21242fb157b26149d3b188f3a8c8653"
SERVER_PATH = Path(os.environ.get("JARVIS_ANDROID_DATA_DIR", "")) / "bin" / "llama-server"
HOST = "127.0.0.1"
PORT = 8080


def _file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _download_model() -> Path:
    MODEL_PATH.parent.mkdir(parents=True, exist_ok=True)
    if MODEL_PATH.is_file():
        if _file_sha256(MODEL_PATH) == MODEL_SHA256:
            return MODEL_PATH
        MODEL_PATH.unlink(missing_ok=True)
    partial = MODEL_PATH.with_suffix(MODEL_PATH.suffix + ".part")
    partial.unlink(missing_ok=True)
    request = urllib.request.Request(MODEL_URL, headers={"User-Agent": "JARVIS-Android"})
    with urllib.request.urlopen(request, timeout=60) as response, partial.open("wb") as out:
        shutil.copyfileobj(response, out)
    if partial.stat().st_size < 100_000_000:
        partial.unlink(missing_ok=True)
        raise RuntimeError("Downloaded GGUF model is unexpectedly small")
    actual_sha256 = _file_sha256(partial)
    if actual_sha256 != MODEL_SHA256:
        partial.unlink(missing_ok=True)
        raise RuntimeError(f"GGUF SHA-256 mismatch: expected {MODEL_SHA256}, got {actual_sha256}")
    partial.replace(MODEL_PATH)
    return MODEL_PATH


def configure_provider() -> Path:
    config_dir = Path(os.environ["JARVIS_CONFIG_DIR"])
    config_dir.mkdir(parents=True, exist_ok=True)
    providers_file = config_dir / "providers.json"
    import json
    data = {"providers": []}
    if providers_file.is_file():
        try:
            data = json.loads(providers_file.read_text())
        except (ValueError, OSError):
            pass
    providers = data.setdefault("providers", [])
    entry = {
        "name": "android-local-llama",
        "type": "api",
        "model": "local-qwen2.5-0.5b",
        "url": f"http://{HOST}:{PORT}/v1",
        "api_key": "",
        "temperature": 0.7,
    }
    # Project-JARVIS' provider pool performs ordered failover. Keep configured
    # online API providers first, then the local model as the guaranteed offline
    # fallback. If no cloud provider is configured, local is the first provider.
    existing = [p for p in providers if isinstance(p, dict) and p.get("name") != entry["name"]]
    online = [p for p in existing if p.get("type") == "api" and
              str(p.get("url", "")).startswith(("https://", "http://"))]
    other = [p for p in existing if p not in online]
    providers[:] = online + other + [entry]
    providers_file.write_text(json.dumps(data, indent=2) + "\n")
    return providers_file


def start_local_llm() -> subprocess.Popen:
    if not SERVER_PATH.is_file():
        raise RuntimeError(f"llama-server binary missing: {SERVER_PATH}")
    model = _download_model()
    configure_provider()
    log_dir = Path(os.environ["JARVIS_ANDROID_DATA_DIR"]) / "logs"
    log_dir.mkdir(parents=True, exist_ok=True)
    log = (log_dir / "llama-server.log").open("ab")
    # The executable depends on llama.cpp shared objects shipped in ../lib.
    env = os.environ.copy()
    lib_dir = str(SERVER_PATH.parent.parent / "lib")
    env["LD_LIBRARY_PATH"] = lib_dir + (":" + env["LD_LIBRARY_PATH"] if env.get("LD_LIBRARY_PATH") else "")
    process = subprocess.Popen(
        [
            str(SERVER_PATH), "-m", str(model), "--host", HOST, "--port", str(PORT),
            "-c", "1024", "-ngl", "0", "--threads", "2",
        ],
        cwd=str(log_dir), stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
        start_new_session=True, env=env,
    )
    import httpx
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError("llama-server exited; inspect logs/llama-server.log")
        try:
            response = httpx.get(f"http://{HOST}:{PORT}/v1/models", timeout=2)
            if response.status_code == 200:
                return process
        except httpx.HTTPError:
            pass
        time.sleep(1)
    process.terminate()
    raise TimeoutError("On-device LLM did not become ready within 90 seconds")
