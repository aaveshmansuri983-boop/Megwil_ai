# JARVIS V3 Android runtime preparation

This is an Android-only overlay and host integration. It does not modify the desktop
JARVIS source or the MEGWIL Android project.

## Preparation pipeline and fail-closed APK gate

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
- `configure_chaquopy.py` configures Python 3.14 with Chaquopy and explicitly selects a matching Python 3.14 build interpreter, copies the
  pinned backend and Android overlay, installs the Kotlin voice plugin,
  adds an Android-only floating voice-start control, and stages runtime
  binaries as APK assets. It only modifies a generated Android project copy.
- `.github/workflows/prepare_jarvis_android_project.yml` initializes that
  generated Android project and checks Python package compatibility, Kotlin
  compilation and Rust Android host integration. It only starts the integrated
  ARM64 APK build when the user's Google Web/Android OAuth configuration and
  stable release-signing keystore have been verified. Otherwise it records the
  exact missing prerequisites and holds the APK build without producing an
  unsigned, debug-signed, or partially configured APK.

### User-owned release prerequisites

Configure these GitHub Actions secrets on the `jarvis-android-fix` repository
before the final APK build:

- `JARVIS_GOOGLE_WEB_CLIENT_ID` (or the existing `GOOGLE_WEB_CLIENT_ID`).
- `JARVIS_GOOGLE_ANDROID_CLIENT_ID`, created in the user's Google Cloud
  project for the exact generated Android application ID and signing SHA-1.
- `JARVIS_ANDROID_STABLE_SIGNING_SHA1`, matching the certificate in the
  release keystore.
- `JARVIS_ANDROID_KEYSTORE_BASE64`, `JARVIS_ANDROID_KEYSTORE_PASSWORD`,
  `JARVIS_ANDROID_KEY_ALIAS`, and `JARVIS_ANDROID_KEY_PASSWORD`.

The workflow validates the keystore certificate SHA-1 against the configured
fingerprint and never prints secret values. Do not commit keystores, passwords,
or OAuth credentials to source control.

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

### Hybrid AI provider pool

- The Android Settings HUD can configure Groq, Google Gemini, OpenRouter, or
  any custom **OpenAI-compatible** API endpoint. Provider name, model, base URL,
  and API key are saved under the app-private
  `files/jarvis-runtime/config/providers.json` path; API keys are not committed
  to source control. Android's app sandbox protects this file, but this overlay
  does not separately encrypt the API key.
- The existing Project-JARVIS ordered provider pool tries configured online API
  providers first and keeps the local llama.cpp model as the final offline
  fallback. If no cloud provider is configured, the local model is first.
- Restart JARVIS after changing a provider. Cloud inference requires a valid
  API key and network access. Provider failure can fall through to the local
  model according to the upstream provider-pool behavior.
- Native Anthropic Messages API is not wired as a dedicated provider here;
  use an OpenAI-compatible gateway if routing an Anthropic model.
- Online model access does not automatically provide live web search or a
  complete offline copy of all AI knowledge. Those need separate retrieval
  integration and a larger local model.

- Native Android TextToSpeech is exposed as a Project-JARVIS TTS provider.
  The selected Android STT and wake-word activation providers use native
  SpeechRecognizer and forward partial/final transcripts into Project-JARVIS.
  They request offline recognition,
  but actual offline availability depends on the speech service and language pack
  installed on the phone. A separate AudioRecord bridge also streams 16 kHz mono
  PCM into a Python sounddevice compatibility shim for a future on-device Vosk
  engine; the Vosk engine/model are not yet bundled.

## Remaining device verification / user inputs

- The latest integrated preparation run `42831107705` passed the generated
  Android/Rust host checks, Chaquopy ARM64 Python dependency resolution, and
  native Kotlin voice bridge compilation. The APK build was deliberately held
  by the OAuth/stable-signing gate; no APK artifact was produced.
- The generated Android `applicationId` is
  `io.jarvisos.jarvis_ui`. Register this exact package name in the user's
  Google Cloud Android OAuth client; do not reuse an older MEGWIL package ID.
- The Web OAuth client ID is configured. The Android OAuth client ID and
  stable signing SHA-1 are not configured in Actions. The release keystore,
  keystore passwords and alias are also not configured. The workflow requires
  `JARVIS_GOOGLE_ANDROID_CLIENT_ID`,
  `JARVIS_ANDROID_STABLE_SIGNING_SHA1`,
  `JARVIS_ANDROID_KEYSTORE_BASE64`,
  `JARVIS_ANDROID_KEYSTORE_PASSWORD`,
  `JARVIS_ANDROID_KEY_ALIAS`, and
  `JARVIS_ANDROID_KEY_PASSWORD`. The keystore certificate must match the
  configured SHA-1. Never send or commit private signing material in source.
- The Vosk native STT engine/model are not bundled. The active STT and wake-word
  providers use Android `SpeechRecognizer`; offline recognition depends on the
  phone's speech service and installed language pack.
- Physical-device end-to-end verification is still required for app-private
  runtime asset installation, sidecar execution, first-launch Qwen model
  download and local server startup, microphone/PCM capture, speech recognition
  and TextToSpeech. CI compilation alone does not prove these device behaviors.
- The selected local model remains Qwen2.5 0.5B Instruct GGUF Q4_K_M, using a
  1,024-token context and two CPU threads for the 4 GB physical-RAM target.
A successful component workflow is not an integrated app. The integrated
workflow gates the final APK build on the user's verified Google OAuth and
stable signing configuration; it will not produce an APK while those inputs are missing.
