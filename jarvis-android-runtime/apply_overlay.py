"""Apply the isolated Android platform overlay to an extracted Project-JARVIS tree."""
from __future__ import annotations

import argparse
import shutil
from pathlib import Path


def apply(source: Path, overlay: Path) -> None:
    package = source / "jarvis" / "platform"
    if not (package / "linux.py").is_file() or not (package / "base.py").is_file():
        raise SystemExit(f"Not a Project-JARVIS source tree: {source}")
    android_src = overlay / "jarvis" / "platform" / "android.py"
    init_src = overlay / "jarvis" / "platform" / "__init__.py"
    if not android_src.is_file() or not init_src.is_file():
        raise SystemExit("Android platform overlay files are incomplete")
    shutil.copy2(android_src, package / "android.py")
    shutil.copy2(init_src, package / "__init__.py")
    print(f"Applied Android platform adapter to {package}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--overlay", type=Path, default=Path(__file__).parent)
    args = parser.parse_args()
    apply(args.source.resolve(), args.overlay.resolve())
