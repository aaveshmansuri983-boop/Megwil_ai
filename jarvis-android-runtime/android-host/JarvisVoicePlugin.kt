package com.jarvis.android.host

import android.Manifest
import android.app.Activity
import android.util.Base64
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
                { pcm, sampleRate, channels ->
                    val event = JSObject()
                    event.put("pcm16Base64", Base64.encodeToString(pcm, Base64.NO_WRAP))
                    event.put("sampleRate", sampleRate)
                    event.put("channels", channels)
                    trigger("audioFrame", event)
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
