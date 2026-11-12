from pathlib import Path
import re, sys

root = Path(sys.argv[1]) / "src-tauri" / "src"
lib_path = root / "lib.rs"
s = lib_path.read_text()

def add_cfg(statement):
    global s
    if statement not in s:
        raise SystemExit("Missing Rust source item: " + statement)
    s = s.replace(statement, '#[cfg(not(target_os = "android"))]\n' + statement, 1)

add_cfg("use tauri::menu::{CheckMenuItemBuilder, MenuBuilder, MenuItemBuilder};")
add_cfg("use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};")
add_cfg("use tauri_plugin_autostart::ManagerExt;")
add_cfg('const TRAY_ID: &str = "main-tray";')
# Remove the desktop-only window decoration call from the Android build copy.
decorations_pattern = re.compile(r"(?m)^[ \t]*\.decorations\(false\)[ \t]*\r?\n")
if not decorations_pattern.search(s):
    raise SystemExit("Desktop-only decorations(false) call not found")
s, removed = decorations_pattern.subn("", s, count=1)
if removed != 1:
    raise SystemExit("Expected to remove exactly one decorations(false) call")

# This script patches only the Android build copy; remove desktop-only widget-window APIs.
for desktop_method in ("        .always_on_top(true)\n", "        .skip_taskbar(true)\n"):
    if desktop_method not in s:
        raise SystemExit("Expected desktop-only widget method not found: " + desktop_method.strip())
    s = s.replace(desktop_method, "", 1)

tray_tooltip_pattern = re.compile(r"(?ms)^fn set_tray_tooltip\([^\n]*\) \{\n.*?^\}")
tooltip_match = tray_tooltip_pattern.search(s)
if not tooltip_match:
    raise SystemExit("Tray tooltip function not found")
desktop_tooltip_fn = tooltip_match.group(0)
android_tooltip_stub = (
    '\n#[cfg(target_os = "android")]\n'
    'fn set_tray_tooltip(_app: &AppHandle, _tooltip: &str) {}\n'
)
s = (
    s[:tooltip_match.start()]
    + '#[cfg(not(target_os = "android"))]\n'
    + desktop_tooltip_fn
    + android_tooltip_stub
    + s[tooltip_match.end():]
)

picker = re.compile(r"#\[tauri::command\]\s*async fn pick_widget_appearance_folder\(.*?\n\}", re.S)
m = picker.search(s)
if not m:
    raise SystemExit("Folder picker function not found")
android_picker = (
    '\n#[cfg(target_os = "android")]\n'
    '#[tauri::command]\n'
    'async fn pick_widget_appearance_folder(_id: String, _app: AppHandle) -> Option<String> { None }\n'
)
s = s[:m.start()] + '#[cfg(not(target_os = "android"))]\n' + m.group(0) + android_picker + s[m.end():]

# Android builds don't have the desktop system tray.
tray_pattern = re.compile(r"let show_hide = MenuItemBuilder::with_id")
m = tray_pattern.search(s)
if m:
    # Mark the full setup block from menu creation through tray build.
    end = s.find("tray_builder.build(app)?;", m.start())
    if end < 0:
        raise SystemExit("Tray build call not found")
    end += len("tray_builder.build(app)?;")
    block = s[m.start():end]
    s = s[:m.start()] + '#[cfg(not(target_os = "android"))]\n            {\n' + ''.join("    " + line + "\n" for line in block.splitlines()) + "            }" + s[end:]

# Autostart registration is desktop-only.
old = '''    tauri::Builder::default()
        .plugin(tauri_plugin_autostart::init(
            tauri_plugin_autostart::MacosLauncher::LaunchAgent,
            None,
        ))
        .plugin(tauri_plugin_dialog::init())'''
new = '''    let builder = tauri::Builder::default();
    #[cfg(not(target_os = "android"))]
    let builder = builder.plugin(tauri_plugin_autostart::init(
        tauri_plugin_autostart::MacosLauncher::LaunchAgent, None,
    ));
    builder.plugin(tauri_plugin_dialog::init())'''
if old not in s:
    raise SystemExit("Autostart plugin builder not found")
s = s.replace(old, new, 1)
lib_path.write_text(s)

ipc_path = root / "ipc.rs"
ipc = ipc_path.read_text()
if '#[cfg(target_os = "android")]' not in ipc:
    pos = ipc.find('    #[cfg(windows)]')
    if pos < 0:
        raise SystemExit("Windows data-dir cfg not found")
    ipc = ipc[:pos] + '''    #[cfg(target_os = "android")]
    {
        std::env::var("JARVIS_DATA_DIR")
            .or_else(|_| std::env::var("JARVIS_ANDROID_DATA_DIR").map(|root| format!("{}/data", root)))
            .or_else(|_| std::env::var("HOME").map(|home| format!("{}/jarvis-runtime/data", home)))
            .unwrap_or_else(|_| "/data/local/tmp/jarvis-runtime/data".into())
    }
''' + ipc[pos:]
    ipc_path.write_text(ipc)
print("JARVIS Android compatibility patch applied")

# Android voice permission prompts are Tauri plugin commands too. The Android
# @TauriPlugin annotation declares OS permissions, but the frontend still needs
# explicit ACL access to check/request those permissions at runtime.
import json

project_root = Path(sys.argv[1])
capability_dir = project_root / "src-tauri" / "capabilities"
capability_dir.mkdir(parents=True, exist_ok=True)
voice_capability = capability_dir / "jarvis-android-voice.json"
capability = {
    "$schema": "../gen/schemas/mobile-schema.json",
    "identifier": "jarvis-android-voice",
    "description": "Android-only JARVIS voice permission prompts",
    "windows": ["main"],
    "platforms": ["android"],
    "permissions": [
        "core:default",
        "jarvis-voice:allow-check-permissions",
        "jarvis-voice:allow-request-permissions",
    ],
}
voice_capability.write_text(json.dumps(capability, indent=2) + "\n", encoding="utf-8")
print("Added Android-only JARVIS voice permission ACL capability")
