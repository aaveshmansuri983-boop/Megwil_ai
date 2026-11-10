package com.jarvis.android.host

/** Static callbacks so the embedded Python STT provider can control Android SpeechRecognizer. */
object AndroidSpeechRouter {
    @Volatile private var startHandler: (() -> Unit)? = null
    @Volatile private var stopHandler: (() -> Unit)? = null
    @Volatile private var availableHandler: (() -> Boolean)? = null

    @JvmStatic
    fun install(start: () -> Unit, stop: () -> Unit, available: () -> Boolean) {
        startHandler = start
        stopHandler = stop
        availableHandler = available
    }

    @JvmStatic
    fun listenOnce() {
        startHandler?.invoke()
    }

    @JvmStatic
    fun isAvailable(): Boolean = availableHandler?.invoke() == true

    @JvmStatic
    fun stop() {
        stopHandler?.invoke()
    }

    @JvmStatic
    fun clear() {
        startHandler = null
        stopHandler = null
        availableHandler = null
    }
}
