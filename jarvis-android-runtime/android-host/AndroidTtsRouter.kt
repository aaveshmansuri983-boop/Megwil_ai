package com.jarvis.android.host

/** Static entry point which the embedded Python TTS provider can call through Chaquopy. */
object AndroidTtsRouter {
    @Volatile private var speakHandler: ((String) -> Unit)? = null
    @Volatile private var stopHandler: (() -> Unit)? = null

    @JvmStatic
    fun install(speak: (String) -> Unit, stop: () -> Unit) {
        speakHandler = speak
        stopHandler = stop
    }

    @JvmStatic
    fun speak(text: String) {
        speakHandler?.invoke(text)
    }

    @JvmStatic
    fun stop() {
        stopHandler?.invoke()
    }

    @JvmStatic
    fun clear() {
        speakHandler = null
        stopHandler = null
    }
}
