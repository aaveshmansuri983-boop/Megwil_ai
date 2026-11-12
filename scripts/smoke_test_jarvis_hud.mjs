import { chromium } from "playwright";

const url = process.argv[2];
if (!url) throw new Error("Usage: node scripts/smoke_test_jarvis_hud.mjs <http-url>");

const browser = await chromium.launch({ headless: true });
try {
  const page = await browser.newPage({
    viewport: { width: 412, height: 915 },
    deviceScaleFactor: 1,
    isMobile: true,
    hasTouch: true,
  });

  // The native Android plugin is compiled separately. Safe test doubles let
  // the real frontend render without starting microphones or a live daemon.
  await page.addInitScript(() => {
    const invoke = async (command) => {
      if (command.endsWith("get_status")) return { connected: false, state: "offline" };
      if (command.endsWith("get_daemon_status")) {
        return { state: "offline", connected: false, last_error: null };
      }
      if (command.endsWith("get_runtime_diagnostics")) {
        return {
          pythonStarted: false,
          backendStatus: JSON.stringify({ state: "not-started" }),
          onlineProviderCount: 0,
          runtimeAssetsPresent: true,
          localModel: "Qwen2.5 0.5B",
          microphonePermission: false,
          speechServiceAvailable: true,
          textToSpeechReady: true,
          runtimeDirectory: "/app-private/jarvis-runtime",
        };
      }
      if (command.endsWith("get_settings")) {
        return { confirmation_mode: "smart", wake_chime_path: "" };
      }
      if (command.endsWith("get_connection_info")) return "/app-private/jarvis-runtime/data/jarvis.sock";
      if (command.endsWith("get_quit_on_daemon_shutdown")) return false;
      if (command.endsWith("list_sessions") ||
          command.endsWith("list_confirmations") ||
          command.endsWith("list_providers") ||
          command.endsWith("list_widgets") ||
          command.endsWith("list_clients")) return [];
      if (command.endsWith("pick_wake_chime_file")) return null;
      if (command.endsWith("check_permissions")) return { microphone: false, camera: false };
      if (command.endsWith("save_online_provider")) {
        return { saved: true, message: "Provider saved in test mode" };
      }
      if (command.endsWith("listen_once") ||
          command.endsWith("start_backend") ||
          command.endsWith("initialize")) return { started: true, initialized: true };
      return { ok: true };
    };
    window.__TAURI__ = {
      core: { invoke },
      event: { listen: async () => () => {} },
    };
  });

  const pageErrors = [];
  page.on("pageerror", (error) => pageErrors.push(String(error)));
  await page.goto(url, { waitUntil: "networkidle", timeout: 30000 });
  await page.waitForSelector("#jarvis-android-dashboard .reactor-art", { timeout: 15000 });

  const orb = await page.locator("#jarvis-android-dashboard .reactor-art").evaluate((img) => {
    const style = getComputedStyle(img);
    const rect = img.getBoundingClientRect();
    return {
      complete: img.complete,
      naturalWidth: img.naturalWidth,
      naturalHeight: img.naturalHeight,
      width: rect.width,
      height: rect.height,
      display: style.display,
      visibility: style.visibility,
      opacity: style.opacity,
      objectFit: style.objectFit,
      borderRadius: style.borderRadius,
      filter: style.filter,
    };
  });

  if (!orb.complete || orb.naturalWidth < 1 || orb.naturalHeight < 1 ||
      orb.width < 24 || orb.height < 24 || orb.display === "none" ||
      orb.visibility === "hidden" || Number(orb.opacity) <= 0 ||
      orb.objectFit !== "contain" || orb.borderRadius !== "0px" ||
      (orb.filter !== "none" && !orb.filter.startsWith("drop-shadow("))) {
    throw new Error("Original orb is not visibly rendered unchanged: " + JSON.stringify(orb));
  }

  await page.locator("#jarvis-settings-open").click();
  await page.locator("#jarvis-provider").selectOption("freellmapi");
  const provider = await page.evaluate(() => ({
    optionPresent: Boolean(document.querySelector('#jarvis-provider option[value="freellmapi"]')),
    url: document.querySelector("#jarvis-provider-url")?.value || "",
    model: document.querySelector("#jarvis-provider-model")?.value || "",
  }));
  if (!provider.optionPresent || provider.url !== "http://127.0.0.1:3001/v1" || provider.model !== "auto") {
    throw new Error("FreeLLMAPI Termux preset is not correctly wired: " + JSON.stringify(provider));
  }

  await page.locator("#jarvis-settings-close").click();
  await page.screenshot({ path: "jarvis-v3-hud-smoke.png", fullPage: true });
  if (pageErrors.length) throw new Error("Frontend runtime errors: " + pageErrors.join(" | "));

  console.log(JSON.stringify({ ok: true, orb, provider, screenshot: "jarvis-v3-hud-smoke.png" }, null, 2));
} finally {
  await browser.close();
}
