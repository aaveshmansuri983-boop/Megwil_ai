# JARVIS V3 Android runtime preparation

This is an Android-only overlay and host integration. It does not modify the desktop
JARVIS source or the MEGWIL Android project.

## Preparation pipeline (APK deliberately disabled)

- `.github/workflows/prepare_jarvis_android_backend.yml` fetches a pinned
  Project-JARVIS source revision, applies the Android platform overlay, checks
  Python syntax, and publishes the Python backend source artifact.
- `.github/workflows/build_jarvis_android_sidecars.yml` cross-compiles
  Dispatch, DMCP and Contextor for Android `arm64-v8a`.
- `.github/workflows/build_jarvis_android_llm.yml` builds llama.cpp for
  Android ARM64, packages the server and all seven required shared libraries,
  strips debug symbols, and writes checksums.
- `.github/workflows/package_jarvis_android_runtime_assets.yml` aggregates
  the ARM64 sidecars, llama-server and shared libraries into one verified
  runtime-assets artifact. It does not build an APK.
- `configure_chaquopy.py` configures Python 3.11 with Chaquopy, copies the
  pinned backend and Android overlay, installs the Kotlin voice plugin,
  and stages runtime binaries as APK assets. It only modifies a generated
  Android project copy.
- `.github/workflows/prepare_jarvis_android_project.yml` initializes that
  generated Android project and checks Python package compatibility, Kotlin
  compilation and Rust Android host integration. It deliberately does not
  run `assemble`, `cargo tauri android build`, APK signing or release.

## Runtime design

- Chaquopy provides the embedded Python interpreter. Android platform detection
  uses `sys.getandroidapilevel()`.
- Runtime binaries and models live under the app-private
  `filesDir/jarvis-runtime` directory.
- Dispatch, DMCP and Contextor are staged in `bin/`. Project-JARVIS manages
  their subprocess lifecycles: Dispatch runs its MCP server, Contextor is a
  JSON-lines stdio child, and DMCP is resolved by Dispatch. They are not
  launched as detached daemons.
- llama-server and its seven required `.so` files are staged in `bin/` and
  `lib/`. The starter Qwen2.5 0.5B Q4_K_M model is downloaded on first launch
  and checked against a pinned SHA-256 before use. The server is loopback-only,
  CPU-only, two threads and a 1024-token context for the 4 GB physical-RAM
  target.
- Native Android TextToSpeech is exposed as a Project-JARVIS TTS provider.
  AudioRecord PCM is passed into a Python sounddevice compatibility shim.
  Android SpeechRecognizer is also available as a one-shot fallback which
  sends recognized text to the existing JARVIS input socket.

## Remaining verification / user inputs

- The generated Android project still needs the no-APK Gradle/Rust preflight
  to finish successfully. Source files existing is not proof of a compiled
  or working Android integration.
- The Vosk native STT engine/model are not yet bundled for Chaquopy. The
  SpeechRecognizer fallback depends on the speech service and offline language
  pack available on the phone.
- `android-host/voice-bridge.ts` exposes the plugin API, but the existing
  frontend still needs its microphone/speech controls connected to those calls.
- Google OAuth Web and Android Client IDs have not been supplied or verified.
  Android OAuth also needs the final application ID and a stable signing
  certificate SHA-1. Never put a client secret in the APK.
- The Python dependency set must pass Chaquopy's Android wheel-resolution
  check; packages requiring unsupported native wheels must be replaced or
  built separately.

A successful component workflow is not an integrated app. The final APK
workflow remains separate and is not started by this preparation pipeline.
