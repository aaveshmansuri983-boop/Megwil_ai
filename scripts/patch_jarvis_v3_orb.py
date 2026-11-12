#!/usr/bin/env python3
"""Apply the verified original-orb and FreeLLMAPI UI overlay to JARVIS V3."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import shutil

ORIGINAL_ORB_SHA256 = "6e44271dca88e5e534663df1fd4dfd49d7f6e072f9595177322ccb08e9f5a264"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Cannot safely patch {label}: expected one source match, found {count}")
    return text.replace(old, new, 1)


def patch(project: Path, orb_image: Path) -> dict[str, object]:
    project = project.resolve()
    orb_image = orb_image.resolve()
    if not (project / "src-tauri" / "Cargo.toml").is_file():
        raise SystemExit(f"Not a JARVIS V3 Tauri project: {project}")
    if not orb_image.is_file():
        raise SystemExit(f"Original orb asset is missing: {orb_image}")

    orb_bytes = orb_image.read_bytes()
    orb_hash = hashlib.sha256(orb_bytes).hexdigest()
    if orb_hash != ORIGINAL_ORB_SHA256:
        raise SystemExit(f"Original orb SHA-256 mismatch: {orb_hash}")

    index_path = project / "src" / "index.html"
    main_path = project / "src" / "main.js"
    styles_path = project / "src" / "styles.css"
    for path in (index_path, main_path, styles_path):
        if not path.is_file():
            raise SystemExit(f"Required JARVIS V3 frontend file is missing: {path}")

    index = index_path.read_text(encoding="utf-8")
    index = replace_once(
        index,
        '<option value="api">API (cloud)</option>',
        '<option value="api">API (cloud / OpenAI-compatible)</option>\n'
        '                    <option value="freellmapi">FreeLLMAPI (Termux on this phone)</option>',
        "provider type options",
    )
    index = replace_once(
        index,
        'placeholder="http://localhost:11434"',
        'placeholder="https://api.example.com/v1 or http://127.0.0.1:3001/v1"',
        "provider URL placeholder",
    )
    index = replace_once(
        index,
        'placeholder="sk-&hellip; (leave blank for Ollama)"',
        'placeholder="Paste your provider gateway key (stored by daemon)"',
        "provider key placeholder",
    )
    index = replace_once(
        index,
        '<div class="empty-logo">\n                <div class="empty-logo-inner">J</div>\n            </div>',
        '<div class="empty-logo" aria-label="JARVIS holographic core">\n'
        '                <img src="assets/jarvis-orb-selected.jpg" alt="Selected cyan-blue JARVIS holographic orb">\n'
        '            </div>',
        "home-screen orb markup",
    )

    main = main_path.read_text(encoding="utf-8")
    main = replace_once(
        main,
        "const providerFormSaveBtn     = document.getElementById('provider-form-save-btn');\n",
        "const providerFormSaveBtn     = document.getElementById('provider-form-save-btn');\n"
        "providerFormType.addEventListener('change', () => {\n"
        "    if (providerFormType.value === 'freellmapi') {\n"
        "        providerFormModel.value = 'auto';\n"
        "        providerFormName.value = 'android-online-freellmapi';\n"
        "        providerFormUrl.value = 'http://127.0.0.1:3001/v1';\n"
        "    }\n"
        "});\n",
        "FreeLLMAPI provider change preset",
    )
    main = replace_once(
        main,
        "providerFormType.value = existing?.type || 'ollama';",
        "providerFormType.value = existing?.name === 'android-online-freellmapi' ? 'freellmapi' : (existing?.type || 'ollama');",
        "existing FreeLLMAPI provider selection",
    )
    main = replace_once(
        main,
        "    providerFormKey.value = '';\n",
        "    providerFormKey.value = '';\n"
        "    if (!existing && providerFormType.value === 'freellmapi') {\n"
        "        providerFormModel.value = 'auto';\n"
        "        providerFormName.value = 'android-online-freellmapi';\n"
        "        providerFormUrl.value = 'http://127.0.0.1:3001/v1';\n"
        "    }\n",
        "new FreeLLMAPI provider defaults",
    )
    main = replace_once(
        main,
        "    const ptype = providerFormType.value;",
        "    const selectedType = providerFormType.value;\n"
        "    const ptype = selectedType === 'ollama' ? 'ollama' : 'api';",
        "FreeLLMAPI provider type mapping",
    )
    main = replace_once(
        main,
        "    const url = providerFormUrl.value.trim();\n    const name = providerFormName.value.trim();",
        "    const url = providerFormUrl.value.trim() || (selectedType === 'freellmapi' ? 'http://127.0.0.1:3001/v1' : '');\n"
        "    const name = providerFormName.value.trim() || (selectedType === 'freellmapi' ? 'android-online-freellmapi' : '');",
        "FreeLLMAPI provider defaults on save",
    )

    styles = styles_path.read_text(encoding="utf-8")
    styles = replace_once(
        styles,
        "#empty-state {\n    flex: 1;\n    display: flex;",
        "#empty-state {\n    flex: 1;\n"
        "    background: radial-gradient(ellipse at 50% 42%, rgba(0, 126, 165, 0.13) 0%, rgba(5, 13, 27, 0.05) 42%, transparent 72%);\n"
        "    display: flex;",
        "home-screen cyan background",
    )
    old_logo_css = (
        ".empty-logo {\n"
        "    width: 72px; height: 72px;\n"
        "    border-radius: 50%;\n"
        "    border: 1.5px solid var(--primary);\n"
        "    display: flex;\n"
        "    align-items: center;\n"
        "    justify-content: center;\n"
        "    animation: logo-pulse 3s ease-in-out infinite;\n"
        "}\n\n"
        ".empty-logo-inner {\n"
        "    width: 56px; height: 56px;\n"
        "    border-radius: 50%;\n"
        "    border: 1px solid var(--primary-dim);\n"
        "    display: flex;\n"
        "    align-items: center;\n"
        "    justify-content: center;\n"
        "    font-size: 28px;\n"
        "    font-weight: bold;\n"
        "    color: var(--primary);\n"
        "}\n\n"
        "@keyframes logo-pulse {\n"
        "    0%, 100% { opacity: 1;   }\n"
        "    50%       { opacity: 0.4; }\n"
        "}\n"
    )
    new_logo_css = (
        ".empty-logo {\n"
        "    width: min( min(76vw, 420px), 48vh);\n"
        "    aspect-ratio: 1 / 1;\n"
        "    flex: 0 1 auto;\n"
        "    display: flex;\n"
        "    align-items: center;\n"
        "    justify-content: center;\n"
        "    border: 0;\n"
        "    border-radius: 0;\n"
        "    overflow: visible;\n"
        "    /* Keep the user's selected orb artwork unchanged. */\n"
        "    animation: none;\n"
        "}\n\n"
        ".empty-logo img {\n"
        "    display: block;\n"
        "    width: 100%;\n"
        "    height: 100%;\n"
        "    object-fit: contain;\n"
        "    border: 0;\n"
        "    border-radius: 0;\n"
        "    filter: drop-shadow(0 0 22px rgba(0, 220, 255, 0.22));\n"
        "}\n\n"
        "@media (max-height: 700px) {\n"
        "    .empty-logo { width: min(68vw, 36vh); }\n"
        "    #empty-state { gap: 10px; padding: 12px; }\n"
        "}\n"
    )
    styles = replace_once(styles, old_logo_css, new_logo_css, "home-screen orb CSS")

    asset_dir = project / "src" / "assets"
    asset_dir.mkdir(parents=True, exist_ok=True)
    asset_path = asset_dir / "jarvis-orb-selected.jpg"
    if asset_path.exists():
        existing_hash = hashlib.sha256(asset_path.read_bytes()).hexdigest()
        if existing_hash != ORIGINAL_ORB_SHA256:
            raise SystemExit(f"Refusing to overwrite a different orb asset: {existing_hash}")
    else:
        shutil.copyfile(orb_image, asset_path)

    if 'value="freellmapi"' not in index or 'assets/jarvis-orb-selected.jpg' not in index:
        raise SystemExit("Patched home screen/provider markup validation failed")
    if "providerFormModel.value = 'auto'" not in main or "http://127.0.0.1:3001/v1" not in main:
        raise SystemExit("Patched FreeLLMAPI preset validation failed")
    if "object-fit: contain" not in styles or "border-radius: 0" not in styles:
        raise SystemExit("Patched orb CSS validation failed")
    if hashlib.sha256(asset_path.read_bytes()).hexdigest() != ORIGINAL_ORB_SHA256:
        raise SystemExit("Copied orb asset failed its original SHA-256 check")

    index_path.write_text(index, encoding="utf-8")
    main_path.write_text(main, encoding="utf-8")
    styles_path.write_text(styles, encoding="utf-8")
    return {
        "project": str(project),
        "orb_sha256": orb_hash,
        "orb_asset": str(asset_path),
        "home_screen_orb": True,
        "freellmapi_provider_ui": True,
        "termux_base_url": "http://127.0.0.1:3001/v1",
        "model": "auto",
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--project", type=Path, required=True)
    parser.add_argument("--orb-image", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(patch(args.project, args.orb_image), indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
