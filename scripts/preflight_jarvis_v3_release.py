#!/usr/bin/env python3
"""Fail-closed release feature gate for the JARVIS V3 Android APK.

This check is independent of Android SDK setup. It inspects the exact source
archive and Android runtime overlay that the build workflow will package.
Missing release gates are reported without starting an APK build.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile

# SHA-256 of the user's original uploaded cyan-blue orb image (2246.jpg).
ORIGINAL_ORB_SHA256 = "6e44271dca88e5e534663df1fd4dfd49d7f6e072f9595177322ccb08e9f5a264"
PROJECT_PREFIX = "jarvis-app-0.1.0/"


def inspect(archive: Path, runtime: Path) -> dict[str, object]:
    result: dict[str, object] = {
        "archive": str(archive),
        "runtime": str(runtime),
        "orb_ready": False,
        "provider_ready": False,
        "runtime_ready": False,
        "ready": False,
        "checks": {},
        "missing": [],
    }
    missing: list[str] = []
    checks: dict[str, object] = {}

    if not archive.is_file():
        missing.append(f"Source archive is missing: {archive}")
        result["missing"] = missing
        return result

    try:
        with zipfile.ZipFile(archive) as zf:
            names = set(zf.namelist())

            def read_source(relative: str) -> bytes | None:
                try:
                    return zf.read(PROJECT_PREFIX + relative)
                except KeyError:
                    return None

            index_bytes = read_source("src/index.html")
            styles_bytes = read_source("src/styles.css")
            cargo_bytes = read_source("src-tauri/Cargo.toml")
            lib_bytes = read_source("src-tauri/src/lib.rs")
            ipc_bytes = read_source("src-tauri/src/ipc.rs")
            config_bytes = read_source("src-tauri/tauri.conf.json")
            orb_bytes = read_source("src/assets/jarvis-orb-selected.jpg")

            index = index_bytes.decode("utf-8", "replace") if index_bytes else ""
            styles = styles_bytes.decode("utf-8", "replace") if styles_bytes else ""
            cargo = cargo_bytes.decode("utf-8", "replace") if cargo_bytes else ""
            lib = lib_bytes.decode("utf-8", "replace") if lib_bytes else ""
            ipc = ipc_bytes.decode("utf-8", "replace") if ipc_bytes else ""
            config = config_bytes.decode("utf-8", "replace") if config_bytes else ""

            orb_hash = hashlib.sha256(orb_bytes).hexdigest() if orb_bytes else None
            orb_referenced = "assets/jarvis-orb-selected.jpg" in index
            image_rule_match = re.search(
                r"\.empty-logo\s+img\s*\{([^}]*)\}", styles, re.IGNORECASE | re.DOTALL
            )
            image_rule = image_rule_match.group(1).lower() if image_rule_match else ""
            filters = re.findall(r"filter\s*:\s*([^;]+)", image_rule)
            # A drop-shadow outside the artwork is allowed. Color-changing,
            # blur, opacity, mask and blend operations are not.
            filter_ok = all(
                re.fullmatch(r"drop-shadow\([^;]+\)", value.strip()) is not None
                for value in filters
            )
            orb_css_ok = bool(
                image_rule_match
                and "object-fit: contain" in image_rule
                and filter_ok
                and "mix-blend-mode" not in image_rule
                and "mask" not in image_rule
            )
            orb_ready = bool(
                orb_bytes
                and orb_hash == ORIGINAL_ORB_SHA256
                and orb_referenced
                and orb_css_ok
            )
            checks["orb"] = {
                "present": orb_bytes is not None,
                "sha256": orb_hash,
                "matches_original": orb_hash == ORIGINAL_ORB_SHA256,
                "referenced_by_home_screen": orb_referenced,
                "css_preserves_artwork": orb_css_ok,
            }
            if not orb_ready:
                missing.append(
                    "Original orb gate failed: src/assets/jarvis-orb-selected.jpg must "
                    "match the user's original SHA-256 and be referenced by the home screen."
                )

            required_source = [
                "src/index.html",
                "src/main.js",
                "src/styles.css",
                "src-tauri/Cargo.toml",
                "src-tauri/src/lib.rs",
                "src-tauri/src/ipc.rs",
                "src-tauri/tauri.conf.json",
            ]
            source_ok = all(PROJECT_PREFIX + item in names for item in required_source)
            checks["source_archive"] = {"required_files_present": source_ok}
            if not source_ok:
                missing.append("One or more required JARVIS V3 source files are missing from the archive.")

            android_cfg_ok = (
                bool(cargo)
                and bool(lib)
                and bool(ipc)
                and bool(config)
                and "jarvis-ui" in cargo
                and "pub fn run()" in lib
                and "pub fn socket_path()" in ipc
            )
            checks["android_source"] = {"tauri_project_present": android_cfg_ok}
            if not android_cfg_ok:
                missing.append("The source archive is not a complete Tauri JARVIS V3 project.")

    except zipfile.BadZipFile:
        missing.append(f"Source archive is not a valid ZIP: {archive}")
        result["missing"] = missing
        return result

    bridge_path = runtime / "android-host" / "voice-bridge.js"
    setup_path = runtime / "FREELLMAPI-ANDROID-SETUP.md"
    bridge = bridge_path.read_text(encoding="utf-8") if bridge_path.is_file() else ""
    setup = setup_path.read_text(encoding="utf-8") if setup_path.is_file() else ""
    termux_clone_documented = bool(
        re.search(
            r"(?im)^\\s*git\\s+clone\\s+https://github\\.com/[^/\\s]+/freellmapi(?:\\.git)?\\s*$",
            setup,
        )
    )
    provider_ready = (
        'value="freellmapi"' in bridge
        and "http://127.0.0.1:3001/v1" in bridge
        and 'model:"auto"' in bridge
        and "npm run dev" in setup
        and termux_clone_documented
        and "http://127.0.0.1:3001/v1" in setup
    )
    checks["freellmapi"] = {
        "provider_option": 'value="freellmapi"' in bridge,
        "loopback_api_url": "http://127.0.0.1:3001/v1" in bridge,
        "automatic_model": 'model:"auto"' in bridge,
        "termux_setup_documented": bool(setup and "npm run dev" in setup and termux_clone_documented),
    }
    if not provider_ready:
        missing.append(
            "FreeLLMAPI gate failed: Android provider settings and Termux gateway "
            "configuration must include model auto and http://127.0.0.1:3001/v1."
        )

    required_runtime = [
        "bootstrap.py", "android_daemon.py", "android_actions.py", "android_audio.py",
        "android_sounddevice.py", "android_stt.py", "android_tts.py", "configure_chaquopy.py",
        "patch_tauri_android.py", "prepare_mcp_android.py", "requirements-android.txt",
        "sidecar_launcher.py", "android-host/JarvisVoicePlugin.kt",
        "android-host/RuntimeAssetInstaller.kt", "android-host/AudioHostBridge.kt",
        "android-host/AndroidActionRouter.kt", "android-host/AndroidSpeechRouter.kt",
        "android-host/AndroidTtsRouter.kt", "android-host/AndroidAudioRouter.kt",
        "tests/test_android_bridge.py", "tests/test_android_runtime.py",
        "llm/bootstrap.py", "jarvis/platform/android.py",
    ]
    absent = [item for item in required_runtime if not (runtime / item).is_file()]
    bootstrap = (runtime / "bootstrap.py").read_text(encoding="utf-8") if (runtime / "bootstrap.py").is_file() else ""
    daemon = (runtime / "android_daemon.py").read_text(encoding="utf-8") if (runtime / "android_daemon.py").is_file() else ""
    plugin = (runtime / "android-host" / "JarvisVoicePlugin.kt").read_text(encoding="utf-8") if (runtime / "android-host" / "JarvisVoicePlugin.kt").is_file() else ""
    configure = (runtime / "configure_chaquopy.py").read_text(encoding="utf-8") if (runtime / "configure_chaquopy.py").is_file() else ""
    runtime_ready = (
        not absent
        and "from jarvis.main import main as jarvis_main" in bootstrap
        and "AndroidGuiSocketClient" in daemon
        and "fun startBackend" in plugin
        and "RuntimeAssetInstaller.install" in plugin
        and 'register_android_plugin("com.jarvis.android.host", "JarvisVoicePlugin")' in configure
    )
    checks["android_daemon_runtime"] = {
        "required_files_present": not absent,
        "missing_files": absent,
        "bootstrap_launches_project_jarvis": "from jarvis.main import main as jarvis_main" in bootstrap,
        "android_gui_socket_client_present": "AndroidGuiSocketClient" in daemon,
        "native_backend_launcher_present": "fun startBackend" in plugin,
        "runtime_assets_installed": "RuntimeAssetInstaller.install" in plugin,
        "tauri_android_plugin_registered": 'register_android_plugin("com.jarvis.android.host", "JarvisVoicePlugin")' in configure,
    }
    if not runtime_ready:
        missing.append(
            "Android daemon/runtime gate failed: embedded Project-JARVIS backend, "
            "AF_UNIX GUI socket client, native Android host plugin and tests must all be present."
        )

    result["orb_ready"] = orb_ready
    result["provider_ready"] = provider_ready
    result["runtime_ready"] = runtime_ready
    result["ready"] = bool(orb_ready and provider_ready and runtime_ready and source_ok and android_cfg_ok)
    result["checks"] = checks
    result["missing"] = missing
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--archive", type=Path, required=True)
    parser.add_argument("--runtime", type=Path, required=True)
    parser.add_argument("--require-all", action="store_true")
    args = parser.parse_args()
    report = inspect(args.archive, args.runtime)
    print(json.dumps(report, indent=2))
    return 1 if args.require_all and not report["ready"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
