package com.jarvis.android.host

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
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
        bridge?.shutdown()
        bridge = null
        val result = JSObject()
        result.put("shutdown", true)
        invoke.resolve(result)
    }
}
