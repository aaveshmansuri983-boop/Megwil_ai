"""Stage verified ARM64 sidecars and expose their paths to Project-JARVIS.

Project-JARVIS owns process lifecycles: it spawns `dispatch serve` through its
MCP transport and starts Contextor as a JSON-lines stdio child. DMCP is invoked
by Dispatch and must be discoverable on PATH. Do not launch these as detached
services here.
"""
from __future__ import annotations
import os
from pathlib import Path

NAMES = ("dispatch", "dmcp", "contextor")


def prepare_sidecars(root: Path) -> dict[str, str]:
    bin_dir = root / "bin"
    if not bin_dir.is_dir():
        raise RuntimeError(f"Android sidecar directory missing: {bin_dir}")
    paths = {}
    for name in NAMES:
        binary = bin_dir / name
        if not binary.is_file():
            raise RuntimeError(f"Android sidecar missing: {binary}")
        binary.chmod(0o700)
        if not os.access(binary, os.X_OK):
            raise RuntimeError(f"Android sidecar is not executable: {binary}")
        paths[name] = str(binary)
    os.environ["PATH"] = str(bin_dir) + os.pathsep + os.environ.get("PATH", "")
    os.environ["DISPATCH_BINARY"] = paths["dispatch"]
    os.environ["DMCP_BINARY"] = paths["dmcp"]
    os.environ["CONTEXTOR_BINARY"] = paths["contextor"]
    return paths
