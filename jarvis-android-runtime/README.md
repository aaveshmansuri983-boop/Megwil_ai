# JARVIS V3 Android backend runtime (isolated overlay)

This folder is an Android-only overlay for the Project-JARVIS Python source.
It does not alter the desktop app or MEGWIL Android project.

## What this patch does now

- Detects Chaquopy/Python-for-Android's `sys.getandroidapilevel()` instead of
  incorrectly selecting the desktop Linux platform just because Android uses
  the Linux kernel.
- Uses one app-private root for config, data, models, logs and all three Unix
  socket endpoints. The GUI socket remains the upstream newline-delimited
  JSON protocol implemented in `jarvis/runtime/io.py`; the sample
  `python/ipc_server.py` is not used.
- Disables desktop notification/service/elevation assumptions.
- Provides a fail-closed preflight. It will not start the daemon while required
  Python modules or the Dispatch/DMCP/Contextor ARM64 sidecars are missing.

## Still required before a complete Android APK

The Android build must embed a Python runtime and apply this overlay to the
actual Project-JARVIS source. ARM64 Android builds of Dispatch, DMCP and
Contextor must be supplied. Android microphone and speech output must be
connected through a native AudioRecord/TextToSpeech host bridge; desktop
`sounddevice`/Piper audio is not assumed to work on Android. A compatible
local LLM runtime/model must also be packaged or installed.

The current Tauri-only APK workflow does not yet package or launch these
components. The preflight intentionally reports NOT READY rather than
pretending the UI APK is a complete assistant.


## Native sidecars

The dedicated GitHub Actions workflow `.github/workflows/build_jarvis_android_sidecars.yml` cross-compiles the upstream Dispatch, DMCP and Contextor Rust programs for Android `arm64-v8a` and publishes a checksum-verified sidecar artifact. A successful compile does not by itself mean those programs are wired into the APK; the host launcher must stage and execute them from app-private storage.
