"""Configure Chaquopy in a generated Tauri Android project (no APK build).

This is an explicit preparation step. It edits only the generated project copy
under src-tauri/gen/android, never the desktop source or MEGWIL app.
"""
from __future__ import annotations
import argparse
import json
import shutil
from pathlib import Path

PLUGIN = 'id("com.chaquo.python")'
VERSION = 'id("com.chaquo.python") version "17.2.0" apply false'


def insert_plugin(path: Path, line: str, after: str | None = None) -> None:
    text = path.read_text()
    if line in text:
        return
    marker = "plugins {"
    if marker not in text:
        if after:
            raise SystemExit(f"Cannot safely locate plugins block in {path}")
        text = marker + "\n    " + line + "\n}\n\n" + text
        path.write_text(text)
        return
    if after:
        pos = text.find(after)
        if pos < 0:
            raise SystemExit(f"Cannot locate required Android Gradle plugin in {path}")
        end = text.find("\n", pos)
        if end < 0:
            end = text.find("}", pos)
        text = text[:end + 1] + "    " + line + "\n" + text[end + 1:]
    else:
        text = text.replace(marker, marker + "\n    " + line, 1)
    path.write_text(text)


def configure(project: Path, runtime: Path, backend_source: Path, runtime_assets: Path) -> None:
    android = project / "src-tauri" / "gen" / "android"
    root_gradle = android / "build.gradle.kts"
    app_gradle = android / "app" / "build.gradle.kts"
    manifest = android / "app" / "src" / "main" / "AndroidManifest.xml"
    for required in (root_gradle, app_gradle, manifest):
        if not required.is_file():
            raise SystemExit(f"Generated Tauri Android file missing: {required}")

    settings = android / "settings.gradle.kts"
    if settings.is_file():
        st = settings.read_text()
        if "pluginManagement" not in st:
            st = """pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

""" + st
        else:
            pm_start = st.find("pluginManagement")
            repo_start = st.find("repositories {", pm_start)
            if repo_start < 0:
                pm_end = st.find("}", pm_start)
                if pm_end < 0:
                    raise SystemExit("Malformed pluginManagement block in settings.gradle.kts")
                st = st[:pm_end] + "\n    repositories { google(); mavenCentral(); gradlePluginPortal() }" + st[pm_end:]
            else:
                repo_end = st.find("}", repo_start)
                repo_block = st[repo_start:repo_end]
                for repository in ("google()", "mavenCentral()", "gradlePluginPortal()"):
                    if repository not in repo_block:
                        st = st[:repo_end] + "\n        " + repository + st[repo_end:]
                        repo_end = st.find("}", repo_start)
        settings.write_text(st)

    insert_plugin(root_gradle, VERSION)
    insert_plugin(app_gradle, PLUGIN, after='id("com.android.application")')
    # Chaquopy ships a native CPython runtime; keep the app ARM64-only.
    app_text_before = app_gradle.read_text()
    # Chaquopy 3.14 publishes ARM64 and x86_64 wheels, but not armeabi-v7a.
    # Force the generated app's native ABI set to ARM64 only, including any
    # Tauri-provided default filters; otherwise Gradle creates universalDebug
    # and Chaquopy rejects its armeabi-v7a variant during configuration.
    import re
    abi_line = '        ndk { abiFilters += listOf("arm64-v8a") }'
    if "abiFilters" in app_text_before:
        app_text_before = re.sub(
            r'(?m)^\s*ndk\s*\{[^}]*abiFilters[^}]*\}\s*\n?',
            abi_line + "\n",
            app_text_before,
        )
        app_text_before = re.sub(
            r'(?m)^\s*abiFilters\s*(?:\+=|=)\s*[^\n]+',
            '            abiFilters.clear()\n            abiFilters.add("arm64-v8a")',
            app_text_before,
        )
    else:
        default_marker = "defaultConfig {"
        pos = app_text_before.find(default_marker)
        if pos < 0:
            raise SystemExit("Cannot locate Android defaultConfig block for Chaquopy ABI filter")
        insert_at = pos + len(default_marker)
        app_text_before = app_text_before[:insert_at] + "\n" + abi_line + app_text_before[insert_at:]
    app_gradle.write_text(app_text_before)

    # Tauri's Rust Gradle plugin always wires both the universal and each
    # architecture-specific merge task. Keep the universal variant enabled,
    # but constrain it to ARM64 and disable unsupported architecture flavors.
    # The universal flavor therefore remains a valid ARM64-only variant, while
    # the separate arm/x86 variants are not configured by Chaquopy.
    gradle_properties = android / "gradle.properties"
    properties_text = gradle_properties.read_text() if gradle_properties.is_file() else ""

    def set_gradle_property(text: str, key: str, value: str) -> str:
        lines = text.splitlines()
        for index, existing in enumerate(lines):
            if existing.partition("=")[0].strip() == key:
                lines[index] = f"{key}={value}"
                break
        else:
            lines.append(f"{key}={value}")
        return "\n".join(lines) + "\n"

    properties_text = set_gradle_property(properties_text, "abiList", "arm64-v8a")
    properties_text = set_gradle_property(properties_text, "archList", "arm64")
    properties_text = set_gradle_property(properties_text, "targetList", "aarch64")
    gradle_properties.write_text(properties_text)

    # Tauri's universal flavor must remain enabled because RustPlugin.kt
    # unconditionally wires mergeUniversal*JniLibFolders. Only unsupported
    # per-architecture flavors are disabled; universal is ARM64-only via
    # abiList above, and arm64 is the matching split flavor.
    app_text = app_gradle.read_text()
    app_text += """

// Chaquopy 3.14 supports ARM64, not 32-bit arm or x86 Android variants.
// Keep Tauri's universal flavor (its ABI list is constrained to arm64-v8a).
androidComponents {
    beforeVariants(selector().all()) { variant ->
        val name = variant.name
        if (!name.startsWith("arm64", ignoreCase = true) &&
            !name.startsWith("universal", ignoreCase = true)) {
            variant.enable = false
        }
    }
}
"""
    app_gradle.write_text(app_text)

    app_text = app_gradle.read_text()
    if "chaquopy {" not in app_text:
        app_text += """
\n// JARVIS Android embedded Python runtime (ARM64 only).
chaquopy {
    defaultConfig {
        buildPython("python3.14")
        version = "3.14"
        pip {
            install("-r", "src/main/python/requirements.txt")
        }
    }
}
"""
        app_gradle.write_text(app_text)

    if not (backend_source / "jarvis" / "main.py").is_file():
        raise SystemExit(f"Project-JARVIS backend source is incomplete: {backend_source}")
    if not (backend_source / "jarvis" / "platform" / "android.py").is_file():
        raise SystemExit("Android platform overlay was not applied to Project-JARVIS source")

    python_dir = android / "app" / "src" / "main" / "python"
    python_dir.mkdir(parents=True, exist_ok=True)
    shutil.copytree(backend_source / "jarvis", python_dir / "jarvis", dirs_exist_ok=True)
    # Project-JARVIS normally disables TTS when PortAudio reports no physical
    # output device. Android uses the native TextToSpeech service instead, so
    # bypass only that desktop audio-device gate for the Android TTS provider.
    component_factory = python_dir / "jarvis" / "core" / "component_factory.py"
    if not component_factory.is_file():
        raise SystemExit("Project-JARVIS component factory is missing")
    factory_text = component_factory.read_text()
    output_gate = """        if not check_audio_output_available():
            logger.warning("Audio output devices not available, TTS disabled")
            return None
"""
    android_output_gate = """        # Native Android TextToSpeech does not require a sounddevice output device.
        if Config.TTS_PROVIDER != "android" and not check_audio_output_available():
            logger.warning("Audio output devices not available, TTS disabled")
            return None
"""
    if android_output_gate not in factory_text:
        if output_gate not in factory_text:
            raise SystemExit("Cannot safely locate Project-JARVIS TTS audio availability check")
        factory_text = factory_text.replace(output_gate, android_output_gate, 1)
        component_factory.write_text(factory_text)
    mcp_source = backend_source / "mcp"
    if not (mcp_source / "client" / "session.py").is_file():
        raise SystemExit("Android-compatible vendored MCP client source is missing")
    shutil.copytree(mcp_source, python_dir / "mcp", dirs_exist_ok=True)
    for name in ("bootstrap.py", "sidecar_launcher.py", "oauth_check.py", "android_audio.py", "android_actions.py", "android_daemon.py", "android_sounddevice.py", "android_tts.py", "requirements-android.txt"):
        src = runtime / name
        if not src.is_file():
            raise SystemExit(f"Android Python runtime input missing: {src}")
        target_name = "requirements.txt" if name == "requirements-android.txt" else name
        shutil.copy2(src, python_dir / target_name)
    llm_src = runtime / "llm"
    if llm_src.is_dir():
        shutil.copytree(llm_src, python_dir / "llm", dirs_exist_ok=True)

    # Bundle the previously verified ARM64 runtime artifact as APK assets.
    if not (runtime_assets / "bin" / "dispatch").is_file() or not (runtime_assets / "bin" / "llama-server").is_file():
        raise SystemExit(f"ARM64 runtime assets are incomplete: {runtime_assets}")
    assets_dir = android / "app" / "src" / "main" / "assets" / "jarvis-runtime"
    shutil.copytree(runtime_assets, assets_dir, dirs_exist_ok=True)

    # Copy the native voice host and Tauri v2 plugin wrapper into the generated app.
    kotlin_dir = android / "app" / "src" / "main" / "java" / "com" / "jarvis" / "android" / "host"
    kotlin_dir.mkdir(parents=True, exist_ok=True)
    for name in ("AudioHostBridge.kt", "JarvisVoicePlugin.kt", "AndroidActionRouter.kt", "RuntimeAssetInstaller.kt", "AndroidTtsRouter.kt", "AndroidSpeechRouter.kt", "AndroidAudioRouter.kt"):
        src = runtime / "android-host" / name
        if not src.is_file():
            raise SystemExit(f"Native Android voice source missing: {src}")
        shutil.copy2(src, kotlin_dir / name)

    # Add the Android TextToSpeech provider to Project-JARVIS' existing factory.
    tts_init = python_dir / "jarvis" / "voice" / "tts" / "__init__.py"
    if not tts_init.is_file():
        raise SystemExit("Project-JARVIS TTS provider factory is missing")
    shutil.copy2(runtime / "android_tts.py", tts_init.parent / "android_tts.py")
    tts_text = tts_init.read_text()
    if 'provider == "android"' not in tts_text:
        anchor_tts = '    raise ValueError(f"Unknown TTS provider:'
        pos_tts = tts_text.find(anchor_tts)
        if pos_tts < 0:
            raise SystemExit("Cannot safely patch Project-JARVIS TTS provider factory")
        tts_text = tts_text[:pos_tts] + '    if provider == "android":\n        from .android_tts import AndroidTTS\n        return AndroidTTS(**kwargs)\n' + tts_text[pos_tts:]
        tts_init.write_text(tts_text)

    # Enable the Tauri global API and add an Android-only microphone start control.
    config_path = project / "src-tauri" / "tauri.conf.json"
    config_data = json.loads(config_path.read_text())
    app_config = config_data.setdefault("app", {})
    app_config["withGlobalTauri"] = True
    security_config = app_config.setdefault("security", {})
    configured_capabilities = security_config.get("capabilities")
    if isinstance(configured_capabilities, list) and "jarvis-android-voice" not in configured_capabilities:
        configured_capabilities.append("jarvis-android-voice")
    config_path.write_text(json.dumps(config_data, indent=2) + "\n")
    web_src = project / "src"
    if not web_src.is_dir():
        raise SystemExit("Tauri frontend source directory is missing")
    shutil.copy2(runtime / "android-host" / "voice-bridge.js", web_src / "jarvis-android-voice.js")
    index_html = web_src / "index.html"
    if not index_html.is_file():
        raise SystemExit("Tauri frontend index.html is missing")
    html = index_html.read_text()
    script_tag = '<script type="module" src="./jarvis-android-voice.js"></script>'
    if script_tag not in html:
        if "</body>" in html:
            html = html.replace("</body>", script_tag + "\n</body>", 1)
        else:
            html += "\n" + script_tag + "\n"
        index_html.write_text(html)

    # Make the typed plugin client available to the frontend source tree.
    web_src = project / "src"
    if web_src.is_dir():
        shutil.copy2(runtime / "android-host" / "voice-bridge.ts", web_src / "jarvis-voice.ts")

    # Add Android's native SpeechRecognizer provider to Project-JARVIS.
    stt_init = python_dir / "jarvis" / "voice" / "stt" / "__init__.py"
    if not stt_init.is_file():
        raise SystemExit("Project-JARVIS STT provider factory is missing")
    shutil.copy2(runtime / "android_stt.py", stt_init.parent / "android_stt.py")
    stt_text = stt_init.read_text()
    if 'provider == "android"' not in stt_text:
        stt_anchor = '    raise ValueError(\n        f"Unknown STT provider:'
        stt_pos = stt_text.find(stt_anchor)
        if stt_pos < 0:
            raise SystemExit("Cannot safely patch Project-JARVIS STT provider factory")
        stt_text = stt_text[:stt_pos] + '    if provider == "android":\n        from .android_stt import AndroidSTT\n        return AndroidSTT(**kwargs)\n' + stt_text[stt_pos:]
        stt_init.write_text(stt_text)

    # Add Android SpeechRecognizer as the wake-word activation provider.
    activation_init = python_dir / "jarvis" / "voice" / "activation" / "__init__.py"
    if not activation_init.is_file():
        raise SystemExit("Project-JARVIS activation provider factory is missing")
    shutil.copy2(runtime / "android_activation.py", activation_init.parent / "android_activation.py")
    activation_text = activation_init.read_text()
    if 'provider == "android"' not in activation_text:
        activation_anchor = '    raise ValueError(f"Unknown activation provider:'
        activation_pos = activation_text.find(activation_anchor)
        if activation_pos < 0:
            raise SystemExit("Cannot safely patch Project-JARVIS activation provider factory")
        activation_text = activation_text[:activation_pos] + '    if provider == "android":\n        from .android_activation import AndroidActivation\n        return AndroidActivation(**kwargs)\n' + activation_text[activation_pos:]
        activation_init.write_text(activation_text)

    # Declare Tauri v2 ACL permissions for the custom Android mobile plugin.
    permissions_dir = project / "src-tauri" / "permissions"
    permissions_dir.mkdir(parents=True, exist_ok=True)
    permission_file = permissions_dir / "jarvis-voice.toml"
    permission_file.write_text("""[[permission]]
identifier = "allow-check-permissions"
description = "Allows checking the Android microphone permission state."
commands.allow = ["checkPermissions"]

[[permission]]
identifier = "allow-request-permissions"
description = "Allows requesting the Android microphone permission."
commands.allow = ["requestPermissions"]

[[permission]]
identifier = "allow-initialize"
description = "Allows initializing the native JARVIS Android voice bridge."
commands.allow = ["initialize"]

[[permission]]
identifier = "allow-start-backend"
description = "Allows starting the embedded JARVIS Python backend."
commands.allow = ["startBackend"]

[[permission]]
identifier = "allow-listen-once"
description = "Allows starting native Android speech recognition."
commands.allow = ["listenOnce"]

[[permission]]
identifier = "allow-stop-listening"
description = "Allows stopping native Android speech recognition."
commands.allow = ["stopListening"]

[[permission]]
identifier = "allow-start-recording"
description = "Allows starting native Android AudioRecord capture."
commands.allow = ["startRecording"]

[[permission]]
identifier = "allow-stop-recording"
description = "Allows stopping native Android AudioRecord capture."
commands.allow = ["stopRecording"]

[[permission]]
identifier = "allow-speak"
description = "Allows native Android TextToSpeech output."
commands.allow = ["speak"]

[[permission]]
identifier = "allow-shutdown"
description = "Allows shutting down the native Android voice bridge."
commands.allow = ["shutdown"]

[[permission]]
identifier = "allow-get-daemon-status"
description = "Allows reading the Android JARVIS runtime socket connection state."
commands.allow = ["getDaemonStatus"]

[[permission]]
identifier = "allow-send-daemon-message"
description = "Allows sending a command through the authenticated JARVIS GUI socket."
commands.allow = ["sendDaemonMessage"]

[[permission]]
identifier = "allow-send-daemon-control"
description = "Allows starting or stopping the JARVIS wake-word listener."
commands.allow = ["sendDaemonControl"]

[[permission]]
identifier = "allow-perform-phone-action"
description = "Allows invoking permission-aware Android intents and device actions."
commands.allow = ["performPhoneAction"]

[[set]]
identifier = "default"
description = "Allows the JARVIS Android voice controls used by the app."
permissions = [
    "allow-check-permissions",
    "allow-request-permissions",
    "allow-initialize",
    "allow-start-backend",
    "allow-listen-once",
    "allow-stop-listening",
    "allow-start-recording",
    "allow-stop-recording",
    "allow-speak",
    "allow-shutdown",
    "allow-get-daemon-status",
    "allow-send-daemon-message",
    "allow-send-daemon-control",
    "allow-perform-phone-action",
]
""")
    capabilities_dir = project / "src-tauri" / "capabilities"
    capabilities_dir.mkdir(parents=True, exist_ok=True)
    voice_capability = {
        "$schema": "../gen/schemas/mobile-schema.json",
        "identifier": "jarvis-android-voice",
        "description": "Allows the main Android window to control native JARVIS voice services.",
        "windows": ["main"],
        "platforms": ["android"],
        "permissions": ["core:default", "core:event:default", "default"],
    }
    (capabilities_dir / "jarvis-android-voice.json").write_text(json.dumps(voice_capability, indent=2) + "\n")

    # Register the Kotlin plugin in the Android-only Rust build copy.
    lib_rs = project / "src-tauri" / "src" / "lib.rs"
    rust = lib_rs.read_text()
    anchor = "let builder = tauri::Builder::default();"
    registration = """
    #[cfg(target_os = "android")]
    let builder = builder.plugin(
        tauri::plugin::Builder::<_, ()>::new("jarvis-voice")
            .setup(|app, api| {
                let data_dir = app.path().app_data_dir()?.join("jarvis-runtime").join("data");
                let runtime_root = app.path().app_data_dir()?.join("jarvis-runtime");
                std::env::set_var("JARVIS_DATA_DIR", data_dir.to_string_lossy().to_string());
                std::env::set_var("JARVIS_ANDROID_DATA_DIR", runtime_root.to_string_lossy().to_string());
                std::env::set_var("HOME", runtime_root.to_string_lossy().to_string());
                api.register_android_plugin("com.jarvis.android.host", "JarvisVoicePlugin")?;
                Ok(())
            })
            .build()
    );
"""
    if "JarvisVoicePlugin" not in rust:
        if anchor not in rust:
            raise SystemExit("Cannot safely locate Android Tauri builder for voice plugin registration")
        rust = rust.replace(anchor, anchor + registration, 1)
        lib_rs.write_text(rust)

    manifest_text = manifest.read_text()
    permissions = (
        '<uses-permission android:name="android.permission.RECORD_AUDIO" />',
        '<uses-permission android:name="android.permission.INTERNET" />',
        '<uses-permission android:name="android.permission.CAMERA" />',
        '<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />',
    )
    manifest_start = manifest_text.find("<manifest")
    manifest_end = manifest_text.find(">", manifest_start)
    if manifest_start < 0 or manifest_end < 0:
        raise SystemExit("AndroidManifest.xml has no manifest root element")
    for permission in permissions:
        if permission not in manifest_text:
            manifest_text = manifest_text[:manifest_end + 1] + "\n    " + permission + manifest_text[manifest_end + 1:]
            manifest_end = manifest_text.find(">", manifest_start)
    if "<queries>" not in manifest_text:
        queries = """<queries>
        <package android:name="com.whatsapp" />
        <package android:name="com.whatsapp.w4b" />
        <package android:name="com.android.chrome" />
        <package android:name="com.google.android.youtube" />
        <package android:name="com.instagram.android" />
    </queries>"""
        application_pos = manifest_text.find("<application")
        if application_pos < 0:
            raise SystemExit("AndroidManifest.xml has no application element for package visibility queries")
        manifest_text = manifest_text[:application_pos] + queries + "\n    " + manifest_text[application_pos:]
    application_start = manifest_text.find("<application")
    application_end = manifest_text.find(">", application_start)
    if application_start < 0 or application_end < 0:
        raise SystemExit("AndroidManifest.xml has no application element")
    application_tag = manifest_text[application_start:application_end + 1]
    if "android:usesCleartextTraffic=" not in application_tag:
        manifest_text = manifest_text[:application_end] + ' android:usesCleartextTraffic="true"' + manifest_text[application_end:]
    manifest.write_text(manifest_text)
    print("Chaquopy Gradle plugin and Android Python sources configured.")
    print("NOTE: Project-JARVIS backend source and package compatibility still require verification.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--project", type=Path, required=True)
    parser.add_argument("--runtime", type=Path, default=Path(__file__).parent)
    parser.add_argument("--backend-source", type=Path, required=True, help="Extracted pinned Project-JARVIS source with Android overlay applied")
    parser.add_argument("--runtime-assets", type=Path, required=True, help="Extracted JARVIS-Android-Runtime-Assets artifact directory")
    args = parser.parse_args()
    configure(args.project.resolve(), args.runtime.resolve(), args.backend_source.resolve(), args.runtime_assets.resolve())
