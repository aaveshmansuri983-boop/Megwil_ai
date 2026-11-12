(() => {
  const api = window.__TAURI__?.core?.invoke;
  if (!api || document.getElementById("jarvis-android-dashboard")) return;
  const listen = window.__TAURI__?.event?.listen;
  const style = document.createElement("style");
  style.textContent = [
    "#jarvis-android-dashboard{position:fixed;inset:0;z-index:2147483000;background:radial-gradient(ellipse at 50% 34%,rgba(135,35,20,.22),transparent 44%),radial-gradient(ellipse at 82% 80%,rgba(180,104,20,.10),transparent 35%),#080a0e;color:#edf1f6;font-family:Inter,Roboto,system-ui,sans-serif;overflow:auto}",
    "#jarvis-android-dashboard *{box-sizing:border-box}",
    "#jarvis-android-dashboard .hud-shell{min-height:100%;max-width:1120px;margin:0 auto;padding:22px;display:flex;flex-direction:column;gap:22px}",
    "#jarvis-android-dashboard .hud-top{display:flex;align-items:center;justify-content:space-between;gap:12px;border-bottom:1px solid rgba(255,255,255,.09);padding-bottom:16px}",
    "#jarvis-android-dashboard .brand{display:flex;align-items:center;gap:12px;font-weight:800;letter-spacing:.16em;font-size:14px}",
    "#jarvis-android-dashboard .brand-mark{width:42px;height:42px;border:1px solid rgba(255,105,64,.72);border-radius:13px;display:grid;place-items:center;color:#ff8b55;background:linear-gradient(145deg,rgba(255,94,54,.18),rgba(255,176,67,.04));box-shadow:0 0 24px rgba(255,79,42,.15);font-size:21px}",
    "#jarvis-android-dashboard .brand small{display:block;color:#87919f;font-size:9px;letter-spacing:.22em;margin-top:4px;font-weight:600}",
    "#jarvis-android-dashboard .daemon-pill{display:flex;align-items:center;gap:8px;border:1px solid rgba(255,255,255,.12);border-radius:999px;padding:8px 12px;color:#aab4c1;font-size:10px;font-weight:800;letter-spacing:.12em;white-space:nowrap}",
    "#jarvis-android-dashboard .daemon-dot{width:7px;height:7px;border-radius:50%;background:#7c8794;box-shadow:0 0 9px currentColor}",
    "#jarvis-android-dashboard .daemon-pill.connected{color:#76e0ae;border-color:rgba(82,210,145,.35)}",
    "#jarvis-android-dashboard .daemon-pill.connected .daemon-dot{background:#55d99a}",
    "#jarvis-android-dashboard .daemon-pill.connecting{color:#ffc06c;border-color:rgba(255,177,77,.35)}",
    "#jarvis-android-dashboard .daemon-pill.connecting .daemon-dot{background:#ffb34d}",
    "#jarvis-android-dashboard .daemon-pill.offline{color:#ff8b78;border-color:rgba(255,102,82,.32)}",
    "#jarvis-android-dashboard .daemon-pill.offline .daemon-dot{background:#ff6652}",
    "#jarvis-android-dashboard .hud-head-actions{display:flex;align-items:center;gap:8px}",
    "#jarvis-android-dashboard .settings-panel{position:fixed;inset:0;z-index:2147483002;background:rgba(0,0,0,.82);display:none;align-items:center;justify-content:center;padding:16px}",
    "#jarvis-android-dashboard .settings-card{width:min(100%,520px);max-height:85vh;overflow:auto;background:#111820;border:1px solid rgba(255,143,83,.5);border-radius:18px;padding:18px;box-shadow:0 18px 60px #000b}",
    "#jarvis-android-dashboard .settings-card h2{margin:0 0 12px;color:#ffb27a;font-size:15px;letter-spacing:.15em}",
    "#jarvis-android-dashboard .settings-card p{color:#aeb8c4;font-size:12px;line-height:1.6}",
    "#jarvis-android-dashboard .settings-status{white-space:pre-wrap;overflow-wrap:anywhere;background:#090d12;border:1px solid rgba(255,255,255,.09);border-radius:12px;padding:12px;color:#dce4ed;font:12px/1.7 monospace}",
    "#jarvis-android-dashboard .hud-grid{display:grid;grid-template-columns:minmax(0,1fr) minmax(300px,.92fr);gap:22px;flex:1;align-items:center}",
    "#jarvis-android-dashboard .core-panel{min-height:520px;display:flex;flex-direction:column;align-items:center;justify-content:center;text-align:center;padding:18px}",
    "#jarvis-android-dashboard .reactor{position:relative;width:clamp(190px,30vw,260px);aspect-ratio:1;border-radius:50%;display:grid;place-items:center;margin:0 auto 24px;isolation:isolate}",
    "#jarvis-android-dashboard .reactor:before{content:'';position:absolute;inset:13%;border-radius:50%;background:radial-gradient(circle,rgba(255,137,63,.27),rgba(255,65,36,.08) 42%,transparent 70%);filter:blur(7px);z-index:-1}",
    "#jarvis-android-dashboard .reactor:after{content:'';position:absolute;inset:0;border-radius:50%;border:1px solid rgba(255,133,75,.42);box-shadow:0 0 32px rgba(255,82,45,.13),inset 0 0 28px rgba(255,123,55,.08)}",
    "#jarvis-android-dashboard .ring{position:absolute;border-radius:50%;border:1px solid rgba(255,167,91,.35);inset:4%;border-left-color:transparent;border-right-color:rgba(255,79,48,.7);animation:jarvis-spin 18s linear infinite}",
    "#jarvis-android-dashboard .ring.ring-two{inset:15%;border-color:rgba(255,93,56,.4);border-top-color:transparent;border-bottom-color:rgba(255,181,90,.7);animation-duration:12s;animation-direction:reverse}",
    "#jarvis-android-dashboard .ring.ring-three{inset:25%;border-style:dashed;border-color:rgba(255,199,119,.35);animation-duration:28s}",
    "#jarvis-android-dashboard .reactor-core{width:38%;height:38%;border-radius:50%;display:grid;place-items:center;border:1px solid rgba(255,198,121,.82);background:radial-gradient(circle at 50% 40%,#ffd59a 0,#ff8a52 17%,#8b291f 48%,#180f12 78%);box-shadow:0 0 25px rgba(255,105,52,.55),0 0 70px rgba(255,69,40,.2),inset 0 0 20px rgba(255,224,172,.5);color:#fff2dc;font-weight:900;font-size:clamp(24px,5vw,36px);text-shadow:0 0 12px #fff}",
    "#jarvis-android-dashboard .reactor[data-state='listening'] .reactor-core{box-shadow:0 0 32px rgba(255,181,83,.8),0 0 90px rgba(255,97,44,.35),inset 0 0 24px rgba(255,236,187,.7)}",
    "#jarvis-android-dashboard .reactor[data-state='error'] .reactor-core{background:radial-gradient(circle,#ffb2a2,#b5322b 52%,#190d10 80%)}",
    "#jarvis-android-dashboard .voice-state{font-size:13px;letter-spacing:.28em;font-weight:900;color:#ffb16e}",
    "#jarvis-android-dashboard .voice-hint{color:#8e99a7;font-size:12px;line-height:1.6;margin-top:8px;max-width:300px}",
    "#jarvis-android-dashboard .primary-row{display:flex;gap:10px;flex-wrap:wrap;justify-content:center;margin-top:22px}",
    "#jarvis-android-dashboard button{font:inherit;cursor:pointer}",
    "#jarvis-android-dashboard .primary-btn{border:1px solid rgba(255,143,83,.65);border-radius:999px;padding:13px 20px;background:linear-gradient(110deg,#7e241f,#c04b2b 58%,#b26b31);color:#fff6e9;font-size:11px;font-weight:900;letter-spacing:.13em;box-shadow:0 0 24px rgba(207,76,43,.16)}",
    "#jarvis-android-dashboard .secondary-btn{border:1px solid rgba(255,255,255,.16);border-radius:999px;padding:13px 18px;background:rgba(255,255,255,.045);color:#d5dce5;font-size:11px;font-weight:800;letter-spacing:.1em}",
    "#jarvis-android-dashboard button:disabled{opacity:.42;cursor:not-allowed}",
    "#jarvis-android-dashboard .console{display:flex;flex-direction:column;gap:12px}",
    "#jarvis-android-dashboard .panel{background:linear-gradient(145deg,rgba(22,27,34,.92),rgba(12,15,20,.95));border:1px solid rgba(255,255,255,.09);border-radius:17px;padding:15px;box-shadow:0 12px 32px rgba(0,0,0,.18)}",
    "#jarvis-android-dashboard .panel-head{font-size:9px;letter-spacing:.2em;font-weight:900;color:#ffad73;margin-bottom:10px}",
    "#jarvis-android-dashboard .transcript{min-height:40px;color:#e5eaf0;font-size:13px;line-height:1.5;overflow-wrap:anywhere}",
    "#jarvis-android-dashboard .input-row{display:flex;gap:8px}",
    "#jarvis-android-dashboard input{min-width:0;flex:1;border:1px solid rgba(255,255,255,.12);border-radius:10px;background:#090c11;color:#f2f5f8;padding:12px;font-size:13px;outline:none}",
    "#jarvis-android-dashboard input:focus{border-color:rgba(255,143,83,.65)}",
    "#jarvis-android-dashboard .send-btn{border:1px solid rgba(255,146,83,.45);border-radius:10px;padding:0 14px;background:rgba(163,62,42,.25);color:#ffd4b2;font-size:10px;font-weight:900;letter-spacing:.1em}",
    "#jarvis-android-dashboard .quick-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px}",
    "#jarvis-android-dashboard .quick-btn{border:1px solid rgba(255,255,255,.1);border-radius:11px;background:rgba(255,255,255,.035);color:#d8e0e9;padding:11px 9px;font-size:11px;font-weight:800;text-align:left}",
    "#jarvis-android-dashboard .quick-btn span{display:block;color:#7f8b99;font-size:9px;font-weight:600;margin-top:4px}",
    "#jarvis-android-dashboard .status-line{font-size:11px;color:#9da8b5;line-height:1.7;overflow-wrap:anywhere}",
    "#jarvis-android-dashboard .status-line strong{color:#dfe6ee;font-weight:700}",
    "#jarvis-android-dashboard .hud-footer{display:flex;align-items:center;justify-content:space-between;gap:12px;border-top:1px solid rgba(255,255,255,.08);padding-top:14px;color:#687381;font-size:9px;letter-spacing:.15em}",
    "#jarvis-android-dashboard .workspace-btn{border:0;background:transparent;color:#aeb8c4;font-size:9px;font-weight:800;letter-spacing:.12em;padding:8px}",
    "#jarvis-android-dashboard-launcher{position:fixed;right:14px;bottom:18px;z-index:2147483001;border:1px solid rgba(255,142,83,.65);border-radius:999px;padding:12px 16px;background:#241116;color:#ffd3b4;font-weight:900;font-size:11px;box-shadow:0 0 22px rgba(255,89,48,.2)}",
    "@keyframes jarvis-spin{to{transform:rotate(360deg)}}",
    "@media(max-width:780px){#jarvis-android-dashboard .hud-shell{padding:16px;gap:16px}#jarvis-android-dashboard .hud-grid{grid-template-columns:1fr;gap:12px}#jarvis-android-dashboard .core-panel{min-height:unset;padding:10px 0 4px}#jarvis-android-dashboard .reactor{width:190px;margin-bottom:15px}#jarvis-android-dashboard .console{padding-bottom:10px}}",
    "@media(max-width:420px){#jarvis-android-dashboard .brand{font-size:11px;letter-spacing:.1em}#jarvis-android-dashboard .daemon-pill{font-size:8px;padding:7px 9px}#jarvis-android-dashboard .hud-footer{font-size:8px}#jarvis-android-dashboard .primary-btn,#jarvis-android-dashboard .secondary-btn{padding:12px 14px}}",
    "@media(prefers-reduced-motion:reduce){#jarvis-android-dashboard .ring{animation:none}#jarvis-android-dashboard *{scroll-behavior:auto!important;transition:none!important}}"
  ].join("\n");
  document.head.appendChild(style);

  const root = document.createElement("div");
  root.id = "jarvis-android-dashboard";
  root.innerHTML = [
    '<div class="hud-shell"><header class="hud-top">',
    '<div class="brand"><div class="brand-mark">J</div><div>JARVIS V3<small>ANDROID CONTROL CORE</small></div></div>',
    '<div class="hud-head-actions"><div id="daemon-pill" class="daemon-pill offline"><i class="daemon-dot"></i><span id="daemon-label">RUNTIME OFFLINE</span></div><button id="jarvis-settings-open" class="secondary-btn" type="button">SETTINGS</button></div>',
    '</header><main class="hud-grid"><section class="core-panel">',
    '<div id="reactor" class="reactor" data-state="standby"><i class="ring ring-one"></i><i class="ring ring-two"></i><i class="ring ring-three"></i><div class="reactor-core">J</div></div>',
    '<div id="voice-state" class="voice-state">STANDBY</div><div id="voice-hint" class="voice-hint">Initialize the local runtime to connect the Android voice bridge and JARVIS command engine.</div>',
    '<div class="primary-row"><button id="jarvis-start" class="primary-btn">INITIALIZE JARVIS</button><button id="jarvis-listen" class="secondary-btn" disabled>START LISTENING</button></div>',
    '</section><section class="console">',
    '<div class="panel"><div class="panel-head">LIVE TRANSCRIPT</div><div id="jarvis-transcript" class="transcript">Awaiting voice input…</div></div>',
    '<div class="panel"><div class="panel-head">COMMAND CONSOLE</div><div class="input-row"><input id="jarvis-command" type="text" placeholder="Ask JARVIS to do something…" maxlength="4000"/><button id="jarvis-send" class="send-btn">SEND</button></div></div>',
    '<div class="panel"><div class="panel-head">QUICK PHONE CONTROLS</div><div class="quick-grid">',
    '<button class="quick-btn" data-action="open_app" data-target="whatsapp">WhatsApp<span>Open app</span></button>',
    '<button class="quick-btn" data-action="open_app" data-target="camera">Camera<span>Open camera</span></button>',
    '<button class="quick-btn" data-action="flashlight">Flashlight<span>Toggle torch</span></button>',
    '<button class="quick-btn" data-action="settings" data-target="settings">Settings<span>Android settings</span></button>',
    '<button class="quick-btn" data-action="volume" data-value="50">Volume 50%<span>Media volume</span></button>',
    '<button class="quick-btn" data-action="settings" data-target="wifi">Wi-Fi settings<span>Open system panel</span></button>',
    '</div></div><div class="panel"><div class="panel-head">DEVICE READINESS</div>',
    '<div id="mic-status" class="status-line"><strong>Microphone:</strong> not initialized</div>',
    '<div id="runtime-status" class="status-line"><strong>Runtime:</strong> offline</div>',
    '<div id="action-status" class="status-line"><strong>Phone actions:</strong> permission-aware Android intents</div>',
    '</div></section></main><footer class="hud-footer"><span>PRIVATE LOCAL RUNTIME • ARM64</span><button id="jarvis-workspace" class="workspace-btn">OPEN WORKSPACE</button></footer></div>'
  ].join("");
  document.body.appendChild(root);
  const settingsPanel = document.createElement("div");
  settingsPanel.className = "settings-panel";
  settingsPanel.innerHTML = '<section class="settings-card"><h2>JARVIS RUNTIME SETTINGS</h2><p>Local AI model aur Android runtime ki readiness yahan check karein. Pehli baar model download karne ke liye internet chahiye; verified model phone mein save hone ke baad local AI offline chal sakta hai.</p><div id="jarvis-settings-status" class="settings-status">Checking device…</div><div class="primary-row"><button id="jarvis-settings-refresh" class="primary-btn" type="button">REFRESH STATUS</button><button id="jarvis-settings-mic" class="secondary-btn" type="button">ENABLE VOICE</button><button id="jarvis-settings-close" class="secondary-btn" type="button">CLOSE</button></div></section>';
  root.appendChild(settingsPanel);

  const $ = selector => root.querySelector(selector);
  const startButton = $("#jarvis-start");
  const listenButton = $("#jarvis-listen");
  const voiceState = $("#voice-state");
  const voiceHint = $("#voice-hint");
  const reactor = $("#reactor");
  const daemonPill = $("#daemon-pill");
  const daemonLabel = $("#daemon-label");
  const transcript = $("#jarvis-transcript");
  const micStatus = $("#mic-status");
  const runtimeStatus = $("#runtime-status");
  const actionStatus = $("#action-status");
  const settingsStatus = $("#jarvis-settings-status");
  const settingsPanelElement = $(".settings-panel");
  let initialized = false;
  let listening = false;
  let userPaused = false;
  let wasConnected = false;
  let torchOn = false;

  async function refreshRuntimeSettings() {
    settingsStatus.textContent = "Checking runtime, local model and microphone…";
    try {
      const response = await api("plugin:jarvis-voice|get_runtime_diagnostics");
      const data = JSON.parse(response?.diagnosticsJson || "{}");
      const model = data.localModelPresent ? "DOWNLOADED — local inference can work offline" : "NOT DOWNLOADED — internet required for first model download";
      settingsStatus.textContent = [
        "Embedded Python: " + (data.pythonStarted ? "running" : "not started"),
        "Runtime binaries: " + (data.runtimeAssetsPresent ? "ready" : "missing / not installed"),
        "Local Qwen model: " + model,
        "Microphone permission: " + (data.microphonePermission ? "granted" : "not granted"),
        "Android speech service: " + (data.speechServiceAvailable ? "available (offline language pack depends on Android speech settings)" : "unavailable — text commands still work"),
        "Text-to-Speech: " + (data.textToSpeechReady ? "ready" : "initializing / unavailable"),
        "App-private runtime: " + (data.runtimeDirectory || "not created")
      ].join("\\n");
    } catch (error) {
      settingsStatus.textContent = "Settings diagnostics unavailable: " + String(error?.message || error);
    }
  }

  $("#jarvis-settings-open").addEventListener("click", () => { settingsPanelElement.style.display = "flex"; refreshRuntimeSettings(); });
  $("#jarvis-settings-close").addEventListener("click", () => { settingsPanelElement.style.display = "none"; });
  $("#jarvis-settings-refresh").addEventListener("click", refreshRuntimeSettings);
  $("#jarvis-settings-mic").addEventListener("click", async () => {
    const button = $("#jarvis-settings-mic");
    button.disabled = true;
    try {
      await window.__TAURI__?.core?.requestPermissions("jarvis-voice");
      const result = await api("plugin:jarvis-voice|listenOnce");
      if (!result?.started) throw new Error(result?.reason || "Android speech service unavailable. Install an offline language pack in Android speech settings.");
      micStatus.textContent = "Microphone: voice recognition started";
      settingsStatus.textContent += "\\n\\nVoice recognition: listening. Offline recognition depends on the Android speech engine and installed language pack.";
    } catch (error) {
      micStatus.textContent = "Microphone: unavailable";
      settingsStatus.textContent += "\\n\\nVoice setup: " + String(error?.message || error);
    } finally {
      button.disabled = false;
    }
  });
  settingsPanelElement.addEventListener("click", event => { if (event.target === settingsPanelElement) settingsPanelElement.style.display = "none"; });

  function setVoiceState(state, hint) {
    const normalized = String(state || "standby").toLowerCase();
    reactor.dataset.state = normalized;
    voiceState.textContent = normalized.replace(/[-_]/g, " ").toUpperCase();
    if (hint) voiceHint.textContent = hint;
  }

  function setDaemonStatus(status) {
    const connected = status && status.connected === true;
    const connecting = status && status.state === "connecting";
    const wasPreviouslyConnected = wasConnected;
    wasConnected = connected;
    daemonPill.className = "daemon-pill " + (connected ? "connected" : (connecting ? "connecting" : "offline"));
    daemonLabel.textContent = connected ? "RUNTIME CONNECTED" : (connecting ? "CONNECTING" : "RUNTIME OFFLINE");
    runtimeStatus.textContent = "Runtime: " + (connected ? "GUI socket handshake confirmed (" + (status.runtime_state || "running") + ")" : (status?.last_error || status?.state || "offline"));
    listenButton.disabled = !connected || !initialized;
    if (connected && initialized) {
      startButton.disabled = true;
      startButton.textContent = "JARVIS ONLINE";
      if (!wasPreviouslyConnected) {
        const command = userPaused ? "stop_listening" : "start_listening";
        api("plugin:jarvis-voice|send_daemon_control", {command}).then(result => {
          if (result?.sent) {
            listening = !userPaused;
            listenButton.textContent = listening ? "STOP LISTENING" : "START LISTENING";
          }
        }).catch(() => {});
      }
    } else if (!connected && initialized) {
      startButton.disabled = false;
      startButton.textContent = "RECONNECT JARVIS";
    }
  }

  async function getDaemonStatus() {
    try {
      const response = await api("plugin:jarvis-voice|get_daemon_status");
      const status = JSON.parse(response?.statusJson || "{}");
      setDaemonStatus(status);
      return status;
    } catch (error) {
      setDaemonStatus({state:"offline",connected:false,last_error:String(error?.message || error)});
      return {state:"offline",connected:false};
    }
  }

  async function waitForDaemon(timeoutMs) {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      const status = await getDaemonStatus();
      if (status.connected) return true;
      await new Promise(resolve => setTimeout(resolve, 1000));
    }
    return false;
  }

  async function initializeJarvis() {
    startButton.disabled = true;
    setVoiceState("preparing", "Requesting microphone access and initializing native Android speech services.");
    micStatus.textContent = "Microphone: requesting permission";
    try {
      try {
        await window.__TAURI__?.core?.requestPermissions("jarvis-voice");
        micStatus.textContent = "Microphone: permission granted";
      } catch (permissionError) {
        micStatus.textContent = "Microphone: unavailable; text control remains enabled";
      }
      await api("plugin:jarvis-voice|initialize");
      micStatus.textContent += "; native bridge initialized";
      setVoiceState("starting", "Starting the on-device JARVIS runtime. First launch may download the local AI model (several hundred MB); keep the app open and connected to the internet. Later launches can work offline.");
      const result = await api("plugin:jarvis-voice|start_backend");
      if (!result?.started) throw new Error(result?.reason || "JARVIS runtime did not start");
      const connected = await waitForDaemon(15 * 60 * 1000);
      if (!connected) throw new Error("JARVIS did not finish connecting within 15 minutes. Open Settings to check runtime assets and whether the first-run local model download completed.");
      initialized = true;
      listening = !userPaused;
      const desiredListening = userPaused ? "stop_listening" : "start_listening";
      await api("plugin:jarvis-voice|send_daemon_control", {command:desiredListening});
      startButton.textContent = "JARVIS ONLINE";
      startButton.disabled = true;
      listenButton.disabled = false;
      listenButton.textContent = listening ? "STOP LISTENING" : "START LISTENING";
      setVoiceState(listening ? "listening" : "standby", listening ? "Wake-word listener is active while JARVIS is in the foreground. Tap Stop to pause it." : "Runtime connected. Wake-word listening is paused.");
    } catch (error) {
      setVoiceState("error", String(error?.message || error));
      startButton.disabled = false;
      startButton.textContent = "RETRY INITIALIZATION";
    }
  }

  async function toggleListening() {
    if (!initialized) return;
    const command = listening ? "stop_listening" : "start_listening";
    const result = await api("plugin:jarvis-voice|send_daemon_control", {command});
    if (!result?.sent) {
      setVoiceState("offline", "The JARVIS runtime connection is unavailable. Reconnect before changing voice listening.");
      return;
    }
    listening = !listening;
    userPaused = !listening;
    listenButton.textContent = listening ? "STOP LISTENING" : "START LISTENING";
    setVoiceState(listening ? "listening" : "standby", listening ? 'Wake-word listener enabled. Say "Jarvis" and then your command.' : "Wake-word listener paused.");
  }

  async function sendCommand() {
    const input = $("#jarvis-command");
    const text = input.value.trim();
    if (!text) return;
    const result = await api("plugin:jarvis-voice|send_daemon_message", {text});
    if (!result?.sent) {
      setVoiceState("offline", "Command was not sent because the JARVIS GUI socket is disconnected.");
      return;
    }
    transcript.textContent = text;
    input.value = "";
    setVoiceState("processing", "Command accepted by the JARVIS GUI socket and queued for the existing command dispatcher.");
  }

  async function phoneAction(action, target, value) {
    try {
      let result = await api("plugin:jarvis-voice|perform_phone_action", {action,target,value});
      if (result?.permissionRequired) {
        actionStatus.textContent = "Phone actions: camera permission required; requesting it now";
        await window.__TAURI__?.core?.requestPermissions("jarvis-voice");
        result = await api("plugin:jarvis-voice|perform_phone_action", {action,target,value});
      }
      actionStatus.textContent = "Phone actions: " + String(result?.message || "No result");
      if (!result?.ok) setVoiceState("error", result?.message || "Android action was not completed");
      else setVoiceState("standby", result.message);
    } catch (error) {
      actionStatus.textContent = "Phone actions: " + String(error?.message || error);
    }
  }

  startButton.addEventListener("click", initializeJarvis);
  listenButton.addEventListener("click", toggleListening);
  $("#jarvis-send").addEventListener("click", sendCommand);
  $("#jarvis-command").addEventListener("keydown", event => {
    if (event.key === "Enter") sendCommand();
  });
  root.querySelectorAll("[data-action]").forEach(button => {
    button.addEventListener("click", () => {
      const action = button.dataset.action;
      const target = button.dataset.target || "";
      let value = button.dataset.value || "";
      if (action === "flashlight") {
        torchOn = !torchOn;
        value = torchOn ? "on" : "off";
      }
      phoneAction(action, target, value);
    });
  });

  $("#jarvis-workspace").addEventListener("click", () => {
    root.style.display = "none";
    let launcher = document.getElementById("jarvis-android-dashboard-launcher");
    if (!launcher) {
      launcher = document.createElement("button");
      launcher.id = "jarvis-android-dashboard-launcher";
      launcher.textContent = "JARVIS HUD";
      launcher.addEventListener("click", () => {
        root.style.display = "block";
        launcher.remove();
      });
      document.body.appendChild(launcher);
    }
  });

  if (listen) {
    listen("plugin:jarvis-voice|backendState", event => {
      const state = event?.payload?.state;
      if (state === "error" || state === "not-ready") {
        setVoiceState("error", event?.payload?.message || "JARVIS runtime is not ready");
        startButton.disabled = false;
        startButton.textContent = "RETRY INITIALIZATION";
      } else if (state === "preparing-assets") {
        setVoiceState("preparing", "Verifying and installing Android runtime files.");
      } else if (state === "starting") {
        setVoiceState("starting", "Starting the embedded JARVIS command engine.");
      } else if (state === "stopped") {
        initialized = false;
        listening = false;
        userPaused = false;
        setVoiceState("offline", "JARVIS runtime stopped.");
        startButton.disabled = false;
        startButton.textContent = "INITIALIZE JARVIS";
      }
    });
    listen("plugin:jarvis-voice|speechState", event => {
      const state = event?.payload?.state;
      if (state) setVoiceState(state, "Native Android speech service state: " + state);
    });
    listen("plugin:jarvis-voice|speechResult", event => {
      const text = event?.payload?.text || "";
      if (text) {
        transcript.textContent = text;
        setVoiceState("processing", "Final transcript received and routed through Project-JARVIS voice event handling.");
      }
    });
    listen("plugin:jarvis-voice|speechError", event => {
      const payload = event?.payload || {};
      setVoiceState("error", "Android speech recognition error: " + (payload.message || payload.code || "unknown"));
    });
  }

  getDaemonStatus();
  window.setInterval(() => {
    if (!document.hidden) getDaemonStatus();
  }, 2500);
})();