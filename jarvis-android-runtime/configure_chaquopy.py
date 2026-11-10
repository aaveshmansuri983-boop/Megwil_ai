"""Configure Chaquopy in a generated Tauri Android project (no APK build).

This is an explicit preparation step. It edits only the generated project copy
under src-tauri/gen/android, never the desktop source or MEGWIL app.
"""
from __future__ import annotations
import argparse
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
        raise SystemExit(f"Cannot safely locate plugins block in {path}")
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
        if "pluginManagement" in st and "mavenCentral()" not in st:
            start = st.find("repositories {", st.find("pluginManagement"))
            if start >= 0:
                end = st.find("}", start)
                st = st[:end] + "\n        mavenCentral()" + st[end:]
                settings.write_text(st)

    insert_plugin(root_gradle, VERSION)
    insert_plugin(app_gradle, PLUGIN, after='id("com.android.application")')
    app_text = app_gradle.read_text()
    if "chaquopy {" not in app_text:
        app_text += """
\n// JARVIS Android embedded Python runtime (ARM64 only).
chaquopy {
    defaultConfig {
        version = "3.11"
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
    for name in ("bootstrap.py", "sidecar_launcher.py", "oauth_check.py", "android_audio.py", "android_sounddevice.py", "android_tts.py", "requirements-android.txt"):
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
    for name in ("AudioHostBridge.kt", "JarvisVoicePlugin.kt", "RuntimeAssetInstaller.kt", "AndroidTtsRouter.kt"):
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
        tts_text = tts_text[:pos_tts] + '    if provider == "android":\\n        from .android_tts import AndroidTTS\\n        return AndroidTTS(**kwargs)\\n' + tts_text[pos_tts:]
        tts_init.write_text(tts_text)

    # Make the typed plugin client available to the frontend source tree.
    web_src = project / "src"
    if web_src.is_dir():
        shutil.copy2(runtime / "android-host" / "voice-bridge.ts", web_src / "jarvis-voice.ts")

    # Register the Kotlin plugin in the Android-only Rust build copy.
    lib_rs = project / "src-tauri" / "src" / "lib.rs"
    rust = lib_rs.read_text()
    anchor = "let builder = tauri::Builder::default();"
    registration = """
    #[cfg(target_os = "android")]
    let builder = builder.plugin(
        tauri::plugin::Builder::new("jarvis-voice")
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
    )
    manifest_start = manifest_text.find("<manifest")
    manifest_end = manifest_text.find(">", manifest_start)
    if manifest_start < 0 or manifest_end < 0:
        raise SystemExit("AndroidManifest.xml has no manifest root element")
    for permission in permissions:
        if permission not in manifest_text:
            manifest_text = manifest_text[:manifest_end + 1] + "\\n    " + permission + manifest_text[manifest_end + 1:]
            manifest_end = manifest_text.find(">", manifest_start)
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
