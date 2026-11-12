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
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            result.put("started", false)
            result.put("reason", "RECORD_AUDIO permission must be granted before backend startup")
            invoke.resolve(result)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
            result.put("started", false)
            result.put("reason", "Android speech recognition service is unavailable")
            invoke.resolve(result)
            return
        }
        val audio = bridge
        if (audio == null || !audio.isSpeechReady()) {
            result.put("started", false)
            result.put("reason", "Initialize the native TextToSpeech bridge and wait until it is ready")
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
