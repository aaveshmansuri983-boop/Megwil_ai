package com.jarvis.android.host

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.Intent
import android.net.LocalSocket
import android.net.LocalSocketAddress
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

/**
 * Tauri v2 Android plugin wrapper for the AudioRecord/TextToSpeech bridge.
 * The Rust host must register this Android plugin as:
 *   identifier = "com.jarvis.android.host"
 *   class      = "JarvisVoicePlugin"
 */
@TauriPlugin(
    permissions = [
        Permission(strings = [Manifest.permission.RECORD_AUDIO], alias = "recordAudio")
    ]
)
class JarvisVoicePlugin(private val activity: Activity) : Plugin(activity) {
    private var bridge: AudioHostBridge? = null
    private var recognizer: SpeechRecognizer? = null

    @Command
    fun initialize(invoke: Invoke) {
        if (bridge == null) {
            bridge = AudioHostBridge(
                activity,
                { pcm, _sampleRate, _channels ->
                    // Feed PCM directly into the embedded Python Vosk input shim.
                    if (Python.isStarted()) {
                        try {
                            Python.getInstance().getModule("android_audio").callAttr("feed", pcm)
                        } catch (_: Throwable) {
                            // Keep the AudioRecord capture thread alive; backend logs its own state.
                        }
                    }
                },
                { state ->
                    val event = JSObject()
                    event.put("state", state)
                    trigger("speechState", event)
                }
            ).also { it.initialize() }
            AndroidTtsRouter.install(
                { text -> bridge?.speak(text) },
                { bridge?.stopSpeaking() }
            )
        }
        val result = JSObject()
        result.put("ready", true)
        invoke.resolve(result)
    }

    @Command
    fun startBackend(invoke: Invoke) {
        val result = JSObject()
        try {
            if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                result.put("started", false)
                result.put("reason", "RECORD_AUDIO permission must be granted before backend startup")
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
            val runtimeRoot = RuntimeAssetInstaller.install(activity)
            if (!Python.isStarted()) Python.start(AndroidPlatform(activity))
            val python = Python.getInstance()
            val environ = python.getModule("os").get("environ")
            environ.callAttr("__setitem__", "JARVIS_ANDROID_DATA_DIR", runtimeRoot.absolutePath)
            environ.callAttr("__setitem__", "JARVIS_ANDROID_VOICE_BRIDGE_READY", "1")
            Thread({
                try {
                    val code = python.getModule("bootstrap").callAttr("main").toInt()
                    val event = JSObject()
                    event.put("state", if (code == 0) "stopped" else "not-ready")
                    event.put("exitCode", code)
                    trigger("backendState", event)
                } catch (error: Throwable) {
                    val event = JSObject()
                    event.put("state", "error")
                    event.put("message", error.message ?: error.javaClass.simpleName)
                    trigger("backendState", event)
                }
            }, "Jarvis-Python-Backend").apply { isDaemon = true; start() }
            result.put("started", true)
            result.put("runtimePath", runtimeRoot.absolutePath)
        } catch (error: Throwable) {
            result.put("started", false)
            result.put("reason", error.message ?: error.javaClass.simpleName)
        }
        invoke.resolve(result)
    }

    @Command
    fun listenOnce(invoke: Invoke) {
        val result = JSObject()
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            result.put("started", false)
            result.put("reason", "RECORD_AUDIO permission is not granted")
            invoke.resolve(result)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
            result.put("started", false)
            result.put("reason", "Android speech recognition service is unavailable")
            invoke.resolve(result)
            return
        }
        try {
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createSpeechRecognizer(activity).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) { trigger("speechState", JSObject().apply { put("state", "listening") }) }
                    override fun onBeginningOfSpeech() { }
                    override fun onRmsChanged(rmsdB: Float) { }
                    override fun onBufferReceived(buffer: ByteArray?) { }
                    override fun onEndOfSpeech() { trigger("speechState", JSObject().apply { put("state", "processing") }) }
                    override fun onError(error: Int) {
                        trigger("speechError", JSObject().apply { put("code", error) })
                        recognizer?.destroy()
                        recognizer = null
                    }
                    override fun onResults(bundle: Bundle?) {
                        val text = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                        if (text.isNotBlank()) sendTextToJarvis(text)
                        trigger("speechResult", JSObject().apply { put("text", text) })
                        recognizer?.destroy()
                        recognizer = null
                    }
                    override fun onPartialResults(bundle: Bundle?) {
                        val text = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
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
            result.put("started", true)
        } catch (error: Throwable) {
            result.put("started", false)
            result.put("reason", error.message ?: error.javaClass.simpleName)
        }
        invoke.resolve(result)
    }

    @Command
    fun stopListening(invoke: Invoke) {
        recognizer?.stopListening()
        recognizer?.destroy()
        recognizer = null
        invoke.resolve(JSObject().apply { put("stopped", true) })
    }

    private fun sendTextToJarvis(text: String) {
        Thread({
            val socketPath = java.io.File(activity.filesDir, "jarvis-runtime/data/input.sock").absolutePath
            var delivered = false
            repeat(10) {
                if (delivered) return@repeat
                try {
                    LocalSocket().use { socket ->
                        socket.connect(LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM))
                        socket.outputStream.use { output ->
                            output.write((text.trim() + "\\n").toByteArray(Charsets.UTF_8))
                            output.flush()
                        }
                    }
                    delivered = true
                } catch (_: Exception) {
                    try { Thread.sleep(250) } catch (_: InterruptedException) { return@Thread }
                }
            }
            trigger("speechDelivery", JSObject().apply {
                put("delivered", delivered)
                if (!delivered) put("reason", "JARVIS input socket is not ready")
            })
        }, "Jarvis-Speech-Input").apply { isDaemon = true; start() }
    }

    @Command
    fun startRecording(invoke: Invoke) {
        val started = bridge?.startRecording() == true
        val result = JSObject()
        result.put("started", started)
        if (!started) result.put("reason", "RECORD_AUDIO permission missing or AudioRecord unavailable")
        invoke.resolve(result)
    }

    @Command
    fun stopRecording(invoke: Invoke) {
        bridge?.stopRecording()
        val result = JSObject()
        result.put("stopped", true)
        invoke.resolve(result)
    }

    @Command
    fun speak(invoke: Invoke) {
        val args = invoke.parseArgs(SpeakArgs::class.java)
        val result = JSObject()
        val ready = bridge != null
        if (ready) bridge?.speak(args.text)
        result.put("accepted", ready && args.text.isNotBlank())
        invoke.resolve(result)
    }

    @Command
    fun shutdown(invoke: Invoke) {
        recognizer?.destroy()
        recognizer = null
        bridge?.shutdown()
        bridge = null
        AndroidTtsRouter.clear()
        val result = JSObject()
        result.put("shutdown", true)
        invoke.resolve(result)
    }
}
