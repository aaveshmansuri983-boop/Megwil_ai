"""Fail-closed sidecar supervisor for the Android app-private runtime.

The exact CLI arguments are supplied by a verified sidecars.json because the
upstream tools' command-line contracts must not be guessed.
"""
from __future__ import annotations
import json
import os
import subprocess
import time
from pathlib import Path

NAMES = ("dispatch", "dmcp", "contextor")


def start_sidecars(root: Path) -> list[subprocess.Popen]:
    bin_dir = root / "bin"
    config = root / "config" / "sidecars.json"
    for name in NAMES:
        binary = bin_dir / name
        if not binary.is_file() or not os.access(binary, os.X_OK):
            raise RuntimeError(f"Android sidecar missing or not executable: {binary}")
    if not config.is_file():
        raise RuntimeError(
            "sidecars.json is missing. Add verified upstream CLI arguments and "
            "health checks before starting sidecar services."
        )
    data = json.loads(config.read_text())
    processes = []
    log_dir = root / "logs"
    log_dir.mkdir(parents=True, exist_ok=True)
    env = os.environ.copy()
    env["PATH"] = str(bin_dir) + os.pathsep + env.get("PATH", "")
    env["JARVIS_ANDROID_DATA_DIR"] = str(root)
    for name in NAMES:
        spec = data.get(name)
        if not isinstance(spec, dict) or not isinstance(spec.get("args"), list):
            raise RuntimeError(f"sidecars.json must define {name}.args as an array")
        argv = [str(bin_dir / name), *[str(v) for v in spec["args"]]]
        log = (log_dir / f"{name}.log").open("ab")
        proc = subprocess.Popen(argv, cwd=str(root), env=env, stdin=subprocess.DEVNULL,
                                stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        time.sleep(0.15)
        if proc.poll() is not None:
            raise RuntimeError(f"{name} exited during startup; inspect logs/{name}.log")
        processes.append(proc)
    return processes
