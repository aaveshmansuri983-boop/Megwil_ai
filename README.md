# JARVIS V3 — Android

This branch is the Android port of JARVIS V3. Android host preparation and
runtime validation are separate from building or uploading an APK.

## Source and Android runtime

- `jarvis-app-android-v3.zip` — the JARVIS V3 Tauri source archive.
- `jarvis-android-runtime/` — the Android-only Project-JARVIS runtime overlay:
  embedded Python bootstrap, AF_UNIX GUI socket client, native Android voice
  and action host, provider settings, Termux setup, and validation tests.
- `scripts/preflight_jarvis_v3_release.py` — release feature gate.
- `scripts/smoke_test_jarvis_hud.mjs` — headless frontend check for the visible
  original orb and FreeLLMAPI provider preset.
- `.github/workflows/build_jarvis_v3_android.yml` — Android preparation and
  release workflow. Relevant pushes run validation; manual dispatch defaults
  to `build_apk=false`.

## Release gates

The workflow may prepare and test the Android host without producing an APK.
An installable APK is built, signed, and uploaded only when all of these
conditions are true:

1. The home screen includes the user's original
   `src/assets/jarvis-orb-selected.jpg` image with the pinned SHA-256
   `6e44271dca88e5e534663df1fd4dfd49d7f6e072f9595177322ccb08e9f5a264`.
   The image must load visibly in the HUD, retain its full aspect ratio, and
   have no recoloring, masking, or crop.
2. Android provider settings expose FreeLLMAPI with model `auto` and the
   loopback endpoint `http://127.0.0.1:3001/v1`.
3. The embedded Project-JARVIS bootstrap, Android native host plugin, private
   runtime asset installer, GUI AF_UNIX socket connection, and automated
   bridge tests are present and validated.
4. The Rust Android host check, Chaquopy dependency check, and Kotlin voice
   bridge compilation pass.
5. The user-owned Google OAuth configuration and stable release keystore pass
   certificate verification.
6. The Chromium HUD smoke test confirms the original orb is actually loaded
   and visible before any APK build step.

A relevant push may build an APK only after every gate above passes. A manual
dispatch additionally requires the user to set `build_apk=true`. If any gate
is missing or fails, APK compilation, signing, and upload are skipped.

## FreeLLMAPI on Termux

FreeLLMAPI is a companion gateway running separately on the same phone. In
JARVIS Settings choose **FreeLLMAPI (Termux gateway)**; its preset is
`http://127.0.0.1:3001/v1` with model `auto`. Enter the unified token created
by the local FreeLLMAPI dashboard in the app's private settings. Do not paste
API keys or signing secrets into chat or commit them to this repository.

See [FREELLMAPI-ANDROID-SETUP.md](jarvis-android-runtime/FREELLMAPI-ANDROID-SETUP.md)
for Termux installation, gateway startup, token setup, and battery-management
notes.

## Verification status

A previous Android runtime preparation run on the separate
`jarvis-android-fix` branch passed Rust Android host checks, Chaquopy Python
dependency resolution, and native Kotlin voice bridge compilation. That run
deliberately skipped the APK build because release OAuth/signing prerequisites
were not complete. The JARVIS V3 branch must pass its own checks before an APK
can be called ready; an artifact from another branch is not a substitute.
