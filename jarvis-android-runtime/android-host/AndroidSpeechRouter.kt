package com.jarvis.android.host

/** Static callbacks so the embedded Python STT provider can control Android SpeechRecognizer. */
object AndroidSpeechRouter {
    @Volatile private var startHandler: (() -> Unit)? = null
    @Volatile private var stopHandler: (() -> Unit)? = null

    @JvmStatic
    fun install(start: () -> Unit, stop: () -> Unit) {
        startHandler = start
        stopHandler = stop
    }

    @JvmStatic
    fun listenOnce() {
        startHandler?.invoke()
    }

    @JvmStatic
    fun stop() {
        stopHandler?.invoke()
    }

    @JvmStatic
    fun clear() {
        startHandler = null
        stopHandler = null
    }
}
