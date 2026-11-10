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


def insert_plugin(path: Path, line: str) -> None:
    text = path.read_text()
    if line in text:
        return
    marker = "plugins {"
    if marker not in text:
        raise SystemExit(f"Cannot safely locate plugins block in {path}")
    text = text.replace(marker, marker + "\n    " + line, 1)
    path.write_text(text)


def configure(project: Path, runtime: Path) -> None:
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
    insert_plugin(app_gradle, PLUGIN)
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

    python_dir = android / "app" / "src" / "main" / "python"
    python_dir.mkdir(parents=True, exist_ok=True)
    for name in ("bootstrap.py", "sidecar_launcher.py", "oauth_check.py", "requirements-android.txt"):
        src = runtime / name
        if not src.is_file():
            raise SystemExit(f"Android Python runtime input missing: {src}")
        target_name = "requirements.txt" if name == "requirements-android.txt" else name
        shutil.copy2(src, python_dir / target_name)
    llm_src = runtime / "llm"
    if llm_src.is_dir():
        shutil.copytree(llm_src, python_dir / "llm", dirs_exist_ok=True)

    manifest_text = manifest.read_text()
    permission = '<uses-permission android:name="android.permission.RECORD_AUDIO" />'
    if permission not in manifest_text:
        manifest_start = manifest_text.find("<manifest")
        pos = manifest_text.find(">", manifest_start)
        if manifest_start < 0 or pos < 0:
            raise SystemExit("AndroidManifest.xml has no manifest root element")
        manifest_text = manifest_text[:pos + 1] + "\n    " + permission + manifest_text[pos + 1:]
        manifest.write_text(manifest_text)
    print("Chaquopy Gradle plugin and Android Python sources configured.")
    print("NOTE: Project-JARVIS backend source and package compatibility still require verification.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--project", type=Path, required=True)
    parser.add_argument("--runtime", type=Path, default=Path(__file__).parent)
    args = parser.parse_args()
    configure(args.project.resolve(), args.runtime.resolve())
