# Android local LLM

JARVIS Android uses **llama.cpp running locally on the phone**, not a hosted
LLM API. The backend already supports OpenAI-compatible providers, so the
Android launcher configures its existing API provider to
`http://127.0.0.1:8080/v1` without changing desktop provider behavior.

## Runtime selection

- Engine: llama.cpp `llama-server`, built for Android `arm64-v8a`.
- Starter model: Qwen2.5 0.5B Instruct, GGUF Q4_K_M (downloaded once on first
  launch; model stays in app-private storage).
- Context: 2,048 tokens initially to keep memory use modest.
- Network: loopback only; inference is on-device and does not require an API key.
- Model size is several hundred MB; first launch requires internet to fetch it.
  Offline use is available after the model download completes.

This is a lightweight starter model chosen for broad ARM64 compatibility and
lower memory demand, not a claim of desktop-class reasoning quality. The model
and llama-server must be included/started by the Android host integration before
the Python backend preflight can report the LLM ready.
