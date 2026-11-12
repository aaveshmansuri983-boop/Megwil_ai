# JARVIS V3 — Android port

This repository branch is being cleaned to keep the JARVIS V3 Android source and its build inputs only.

## Source

- `jarvis-app-android-v3.zip` — JARVIS V3 source archive.
- `MEGWIL-additive-features.zip` — additive source bundle consumed by the current Android preparation workflow; retained as a build input until its contents are merged and verified.
- `scripts/prepare_android_port.sh` and `scripts/fix_android_port.py` — Android port preparation scripts.
- `.github/workflows/build_jarvis_android.yml` — Android build and emulator smoke-test workflow.

The unrelated standalone MEGWIL app and its build workflow have been removed from this branch. Repository Actions secrets are not changed by this cleanup.

## Build status

A successful workflow run and emulator smoke test are required before calling an APK ready. The current workflow produces an APK inside a ZIP artifact; a direct APK release asset is not yet configured.
