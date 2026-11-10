(() => {
  const api = window.__TAURI__?.core?.invoke;
  if (!api || document.getElementById("jarvis-android-voice-start")) return;

  const listen = window.__TAURI__?.event?.listen;

  const wrap = document.createElement("div");
  wrap.id = "jarvis-android-voice-controls";
  Object.assign(wrap.style, {
    position: "fixed", right: "18px", bottom: "18px", zIndex: "2147483000",
    display: "flex", flexDirection: "column", alignItems: "flex-end", gap: "8px",
    fontFamily: "system-ui, sans-serif"
  });

  const status = document.createElement("div");
  status.textContent = "JARVIS Android voice is ready to start";
  Object.assign(status.style, {
    color: "#fff", background: "rgba(20,20,24,.92)", padding: "8px 12px",
    borderRadius: "12px", fontSize: "12px", maxWidth: "230px"
  });

  const button = document.createElement("button");
  button.id = "jarvis-android-voice-start";
  button.type = "button";
  button.textContent = "🎙 Start JARVIS";
  Object.assign(button.style, {
    border: "0", borderRadius: "999px", padding: "14px 20px",
    background: "#1f6feb", color: "#fff", fontSize: "15px",
    fontWeight: "700", boxShadow: "0 4px 18px rgba(0,0,0,.3)"
  });

  button.addEventListener("click", async () => {
    button.disabled = true;
    status.textContent = "Requesting microphone permission…";
    try {
      await api("plugin:jarvis-voice|requestPermissions", { permissions: ["recordAudio"] });
      await api("plugin:jarvis-voice|initialize");
      await new Promise(resolve => setTimeout(resolve, 1200));
      status.textContent = "Starting Python, local LLM and voice services…";
      const result = await api("plugin:jarvis-voice|startBackend");
      if (!result?.started) throw new Error(result?.reason || "JARVIS backend did not start");
      button.textContent = "✓ JARVIS started";
      button.style.background = "#238636";
      status.textContent = 'JARVIS is starting. Say "Jarvis" when voice recognition is ready.';
    } catch (error) {
      status.textContent = String(error?.message || error);
      button.disabled = false;
    }
  });

  if (listen) {
    listen("plugin:jarvis-voice|backendState", event => {
      const state = event?.payload?.state;
      if (state === "error" || state === "not-ready") {
        status.textContent = event?.payload?.message || "JARVIS backend is not ready";
        button.disabled = false;
        button.textContent = "🎙 Start JARVIS";
        button.style.background = "#1f6feb";
      } else if (state === "preparing-assets") {
        status.textContent = "Verifying and installing Android runtime files…";
      } else if (state === "starting") {
        status.textContent = "JARVIS backend is starting…";
      }
    });
  }

  wrap.append(status, button);
  document.body.appendChild(wrap);
})();
