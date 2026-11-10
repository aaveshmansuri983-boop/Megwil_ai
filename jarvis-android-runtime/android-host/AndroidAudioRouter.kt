package com.jarvis.android.host

/** Chaquopy entry point for the sounddevice-compatible raw PCM input stream. */
object AndroidAudioRouter {
    @Volatile private var startHandler: (() -> Boolean)? = null
    @Volatile private var stopHandler: (() -> Unit)? = null

    @JvmStatic
    fun install(start: () -> Boolean, stop: () -> Unit) {
        startHandler = start
        stopHandler = stop
    }

    @JvmStatic
    fun startRecording(): Boolean = startHandler?.invoke() == true

    @JvmStatic
    fun stopRecording() {
        stopHandler?.invoke()
    }

    @JvmStatic
    fun clear() {
        startHandler = null
        stopHandler = null
    }
}
