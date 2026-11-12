package com.jarvis.android.host

import android.Manifest
import android.app.Activity
import org.json.JSONObject
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import app.tauri.annotation.Command
import app.tauri.annotation.InvokeArg
import app.tauri.annotation.Permission
import app.tauri.annotation.TauriPlugin
import app.tauri.plugin.Invoke
import app.tauri.plugin.JSObject
import app.tauri.plugin.Plugin

@InvokeArg
internal class SpeakArgs {
    lateinit var text: String
}

@InvokeArg
internal class DaemonMessageArgs {
    lateinit var text: String
}

@InvokeArg
internal class DaemonControlArgs {
    lateinit var command: String
}

@InvokeArg
internal class PhoneActionArgs {
    lateinit var action: String
    var target: String? = null
    var value: String? = null
}

@InvokeArg
internal class OnlineProviderArgs {
    lateinit var provider: String
    lateinit var baseUrl: String
    lateinit var model: String
    var apiKey: String? = null
}

/**
 * Tauri v2 Android host for AudioRecord, Android SpeechRecognizer and TextToSpeech.
 * Register as com.jarvis.android.host.JarvisVoicePlugin from the Rust Android host.
 */
@TauriPlugin(
    permissions = [
        Permission(strings = [Manifest.permission.RECORD_AUDIO], alias = "recordAudio"),
        Permission(strings = [Manifest.permission.CAMERA], alias = "camera")
    ]
)
class JarvisVoicePlugin(private val activity: Activity) : Plugin(activity) {
    private var bridge: AudioHostBridge? = null
    private var recognizer: SpeechRecognizer? = null
    @Volatile private var backendStarted = false

    @Command
    fun initialize(invoke: Invoke) {
        if (bridge == null) {
            bridge = AudioHostBridge(
                activity,
                { pcm, _sampleRate, _channels ->
                    if (Python.isStarted()) {
                        try {
                            Python.getInstance().getModule("android_audio").callAttr("feed", pcm)
                        } catch (_: Throwable) {
                            // Do not terminate the native capture thread on a Python callback error.
                        }
                    }
                },
                { state ->
                    trigger("speechState", JSObject().apply { put("state", state) })
                }
            ).also { it.initialize() }
            AndroidTtsRouter.install(
                { text -> activity.runOnUiThread { bridge?.speak(text) } },
                { activity.runOnUiThread { bridge?.stopSpeaking() } }
            )
            AndroidAudioRouter.install(
                { bridge?.startRecording() == true },
                { bridge?.stopRecording() }
            )
            AndroidSpeechRouter.install(
                { activity.runOnUiThread { startSpeechRecognitionInternal() } },
                { activity.runOnUiThread { stopSpeechRecognitionInternal() } },
                { activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED && SpeechRecognizer.isRecognitionAvailable(activity) }
            )
        }
        AndroidActionRouter.install(activity)
        invoke.resolve(JSObject().apply { put("ready", true) })
    }

    @Command
    fun startBackend(invoke: Invoke) {
        val result = JSObject()
        if (backendStarted) {
            result.put("started", true)
            result.put("alreadyRunning", true)
            invoke.resolve(result)
            return
        }
        // The local command engine must remain usable without microphone access,
        // Google speech services, or an initialized TTS engine. Voice is optional;
        // keyboard commands and the on-device LLM can still run offline.
        if (bridge == null) {
            result.put("started", false)
            result.put("reason", "Native Android bridge is not initialized; retry initialization")
            invoke.resolve(result)
            return
        }

        backendStarted = true
        trigger("backendState", JSObject().apply { put("state", "preparing-assets") })
        Thread({
            try {
                // Copying and hashing native runtime assets can take time; never do it on the UI thread.
                val runtimeRoot = RuntimeAssetInstaller.install(activity)
                if (!Python.isStarted()) Python.start(AndroidPlatform(activity))
                val python = Python.getInstance()
                val osModule = requireNotNull(python.getModule("os")) {
                    "Chaquopy os module is unavailable"
                }
                val environ = requireNotNull(osModule.get("environ")) {
                    "Chaquopy os.environ is unavailable"
                }
                environ.callAttr("__setitem__", "JARVIS_ANDROID_DATA_DIR", runtimeRoot.absolutePath)
                environ.callAttr("__setitem__", "JARVIS_ANDROID_VOICE_BRIDGE_READY", "1")
                trigger("backendState", JSObject().apply { put("state", "starting") })
                Thread({
                    try {
                        val code = python.getModule("bootstrap").callAttr("main").toInt()
                        backendStarted = false
                        trigger("backendState", JSObject().apply {
                            put("state", if (code == 0) "stopped" else "not-ready")
                            put("exitCode", code)
                        })
                    } catch (error: Throwable) {
                        backendStarted = false
                        trigger("backendState", JSObject().apply {
                            put("state", "error")
                            put("message", error.message ?: error.javaClass.simpleName)
                        })
                    }
                }, "Jarvis-Python-Backend").apply { isDaemon = true; start() }
                result.put("started", true)
                result.put("runtimePath", runtimeRoot.absolutePath)
            } catch (error: Throwable) {
                backendStarted = false
                result.put("started", false)
                result.put("reason", error.message ?: error.javaClass.simpleName)
                trigger("backendState", JSObject().apply {
                    put("state", "error")
                    put("message", error.message ?: error.javaClass.simpleName)
                })
            }
            invoke.resolve(result)
        }, "Jarvis-Android-Prepare").apply { isDaemon = true; start() }
    }

    private fun startSpeechRecognitionInternal(): Boolean {
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        if (!SpeechRecognizer.isRecognitionAvailable(activity)) return false
        return try {
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createSpeechRecognizer(activity).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        trigger("speechState", JSObject().apply { put("state", "listening") })
                    }
                    override fun onBeginningOfSpeech() { }
                    override fun onRmsChanged(rmsdB: Float) { }
                    override fun onBufferReceived(buffer: ByteArray?) { }
                    override fun onEndOfSpeech() {
                        trigger("speechState", JSObject().apply { put("state", "processing") })
                    }
                    override fun onError(error: Int) {
                        if (Python.isStarted()) {
                            try { Python.getInstance().getModule("android_audio").callAttr("feed_text", "", true) } catch (_: Throwable) { }
                        }
                        trigger("speechError", JSObject().apply { put("code", error) })
                        recognizer?.destroy()
                        recognizer = null
                    }
                    override fun onResults(bundle: Bundle?) {
                        val text = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                        if (Python.isStarted()) {
                            try { Python.getInstance().getModule("android_audio").callAttr("feed_text", text, true) } catch (_: Throwable) { }
                        }
                        trigger("speechResult", JSObject().apply { put("text", text) })
                        recognizer?.destroy()
                        recognizer = null
                    }
                    override fun onPartialResults(bundle: Bundle?) {
                        val text = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                        if (Python.isStarted() && text.isNotBlank()) {
                            try { Python.getInstance().getModule("android_audio").callAttr("feed_text", text, false) } catch (_: Throwable) { }
                        }
                        if (text.isNotBlank()) trigger("speechPartial", JSObject().apply { put("text", text) })
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) { }
                })
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
            recognizer?.startListening(intent)
            true
        } catch (error: Throwable) {
            trigger("speechError", JSObject().apply { put("message", error.message ?: error.javaClass.simpleName) })
            recognizer?.destroy()
            recognizer = null
            false
        }
    }

    private fun stopSpeechRecognitionInternal() {
        recognizer?.stopListening()
        recognizer?.destroy()
        recognizer = null
    }

    @Command
    fun listenOnce(invoke: Invoke) {
        val started = startSpeechRecognitionInternal()
        invoke.resolve(JSObject().apply {
            put("started", started)
            if (!started) put("reason", "Permission missing or Android speech recognition unavailable")
        })
    }

    @Command
    fun stopListening(invoke: Invoke) {
        stopSpeechRecognitionInternal()
        invoke.resolve(JSObject().apply { put("stopped", true) })
    }

    @Command
    fun startRecording(invoke: Invoke) {
        val started = bridge?.startRecording() == true
        invoke.resolve(JSObject().apply {
            put("started", started)
            if (!started) put("reason", "RECORD_AUDIO permission missing or AudioRecord unavailable")
        })
    }

    @Command
    fun stopRecording(invoke: Invoke) {
        bridge?.stopRecording()
        invoke.resolve(JSObject().apply { put("stopped", true) })
    }

    @Command
    fun speak(invoke: Invoke) {
        val args = invoke.parseArgs(SpeakArgs::class.java)
        val ready = bridge != null
        if (ready) bridge?.speak(args.text)
        invoke.resolve(JSObject().apply { put("accepted", ready && args.text.isNotBlank()) })
    }


    @Command
    fun getDaemonStatus(invoke: Invoke) {
        val statusJson = try {
            if (Python.isStarted()) {
                Python.getInstance().getModule("android_daemon").callAttr("status_json").toString()
            } else {
                JSONObject().put("state", "offline").put("connected", false)
                    .put("last_error", "Embedded Python runtime is not started").toString()
            }
        } catch (error: Throwable) {
            JSONObject().put("state", "offline").put("connected", false)
                .put("last_error", error.message ?: error.javaClass.simpleName).toString()
        }
        invoke.resolve(JSObject().apply { put("statusJson", statusJson) })
    }

    @Command
    fun saveOnlineProvider(invoke: Invoke) {
        val args = invoke.parseArgs(OnlineProviderArgs::class.java)
        val provider = args.provider.trim().lowercase()
        val allowed = setOf("groq", "gemini", "openrouter", "custom")
        val baseUrl = args.baseUrl.trim().trimEnd('/')
        val model = args.model.trim()
        val apiKey = args.apiKey?.trim().orEmpty()
        try {
            require(provider in allowed) { "Unsupported provider" }
            require(baseUrl.startsWith("https://") ||
                baseUrl.startsWith("http://127.0.0.1") ||
                baseUrl.startsWith("http://localhost")) { "Use HTTPS for online providers" }
            require(model.isNotBlank() && model.length <= 160) { "Enter a valid model name" }
            require(apiKey.isNotBlank() && apiKey.length <= 512) { "Enter the provider API key" }
            val root = java.io.File(activity.filesDir, "jarvis-runtime/config")
            root.mkdirs()
            val file = java.io.File(root, "providers.json")
            val current = if (file.isFile) JSONObject(file.readText()) else JSONObject()
            val old = current.optJSONArray("providers") ?: org.json.JSONArray()
            val next = org.json.JSONArray()
            val name = "android-online-" + provider
            val newEntry = JSONObject().apply {
                put("name", name)
                put("type", "api")
                put("model", model)
                put("url", baseUrl)
                put("api_key", apiKey)
                put("temperature", 0.4)
            }
            var inserted = false
            for (index in 0 until old.length()) {
                val item = old.optJSONObject(index) ?: continue
                if (item.optString("name") == name) continue
                if (!inserted && item.optString("name") == "android-local-llama") {
                    next.put(newEntry)
                    inserted = true
                }
                next.put(item)
            }
            if (!inserted) next.put(newEntry)
            current.put("providers", next)
            file.writeText(current.toString(2) + "\\n")
            file.setReadable(false, false)
            file.setWritable(false, false)
            file.setReadable(true, true)
            file.setWritable(true, true)
            invoke.resolve(JSObject().apply {
                put("saved", true)
                put("provider", provider)
                put("model", model)
                put("message", "Provider saved in private app storage. Restart JARVIS to apply it.")
            })
        } catch (error: Throwable) {
            invoke.resolve(JSObject().apply {
                put("saved", false)
                put("reason", error.message ?: error.javaClass.simpleName)
            })
        }
    }

    @Command
    fun getRuntimeDiagnostics(invoke: Invoke) {
        val root = java.io.File(activity.filesDir, "jarvis-runtime")
        val model = java.io.File(root, "models/Qwen2.5-0.5B-Instruct-Q4_K_M.gguf")
        val binaries = listOf("dispatch", "dmcp", "contextor", "llama-server")
        val libraries = listOf("libllama-server-impl.so", "libllama-common.so", "libmtmd.so", "libllama.so", "libggml.so", "libggml-cpu.so", "libggml-base.so")
        val assetsPresent = binaries.all { java.io.File(root, "bin/$it").isFile } &&
            libraries.all { java.io.File(root, "lib/$it").isFile }
        val diagnostics = JSONObject().apply {
            put("pythonStarted", Python.isStarted())
            put("runtimeAssetsPresent", assetsPresent)
            put("localModelPresent", model.isFile && model.length() > 100_000_000L)
            put("localModelBytes", if (model.isFile) model.length() else 0L)
            put("microphonePermission", activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            put("speechServiceAvailable", SpeechRecognizer.isRecognitionAvailable(activity))
            put("textToSpeechReady", bridge?.isSpeechReady() == true)
            put("runtimeDirectory", root.absolutePath)
        }
        invoke.resolve(JSObject().apply { put("diagnosticsJson", diagnostics.toString()) })
    }

    @Command
    fun sendDaemonMessage(invoke: Invoke) {
        val args = invoke.parseArgs(DaemonMessageArgs::class.java)
        val sent = try {
            Python.isStarted() && Python.getInstance().getModule("android_daemon")
                .callAttr("send_message", args.text).toString().equals("True", true)
        } catch (_: Throwable) { false }
        invoke.resolve(JSObject().apply { put("sent", sent) })
    }

    @Command
    fun sendDaemonControl(invoke: Invoke) {
        val args = invoke.parseArgs(DaemonControlArgs::class.java)
        val sent = try {
            Python.isStarted() && Python.getInstance().getModule("android_daemon")
                .callAttr("send_control", args.command).toString().equals("True", true)
        } catch (_: Throwable) { false }
        invoke.resolve(JSObject().apply { put("sent", sent) })
    }

    @Command
    fun performPhoneAction(invoke: Invoke) {
        val args = invoke.parseArgs(PhoneActionArgs::class.java)
        val result = try {
            JSONObject(AndroidActionRouter.execute(args.action, args.target, args.value))
        } catch (error: Throwable) {
            JSONObject().put("ok", false).put("message", error.message ?: error.javaClass.simpleName)
        }
        invoke.resolve(JSObject().apply {
            put("ok", result.optBoolean("ok", false))
            put("message", result.optString("message", "Android action failed"))
            put("permissionRequired", result.optBoolean("permission_required", false))
        })
    }

    @Command
    fun shutdown(invoke: Invoke) {
        try {
            if (Python.isStarted()) Python.getInstance().getModule("android_daemon").callAttr("send_control", "shutdown_request")
        } catch (_: Throwable) { }
        stopSpeechRecognitionInternal()
        bridge?.shutdown()
        bridge = null
        AndroidTtsRouter.clear()
        AndroidSpeechRouter.clear()
        AndroidAudioRouter.clear()
        AndroidActionRouter.clear()
        invoke.resolve(JSObject().apply { put("shutdown", true) })
    }
}
