"""Platform selection including Android's Linux-kernel app sandbox."""
from __future__ import annotations

import sys

if hasattr(sys, "getandroidapilevel"):
    from .android import AndroidPlatform as _Cls
else:
    import platform as _platform

    _system = _platform.system()
    if _system == "Darwin":
        from .macos import MacOSPlatform as _Cls
    elif _system == "Windows":
        from .windows import WindowsPlatform as _Cls
    else:
        from .linux import LinuxPlatform as _Cls

current: _Cls = _Cls()  # type: ignore[assignment]
