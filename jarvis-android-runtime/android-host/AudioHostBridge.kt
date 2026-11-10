package com.jarvis.android.host

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.speech.tts.TextToSpeech
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Native Android voice host. The Tauri/Android host must instantiate this class
 * after RECORD_AUDIO permission is granted and forward PCM frames to the
 * existing JARVIS input transport. No desktop sounddevice/Piper dependency.
 */
class AudioHostBridge(
    private val context: Context,
    private val onPcm16: (ByteArray, Int, Int) -> Unit,
    private val onSpeechState: (String) -> Unit = {}
) : TextToSpeech.OnInitListener {
    private val running = AtomicBoolean(false)
    private var recorder: AudioRecord? = null
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) tts?.language = Locale.getDefault()
        onSpeechState(if (ttsReady) "ready" else "unavailable")
    }

    fun initialize() {
        if (tts == null) tts = TextToSpeech(context.applicationContext, this)
    }

    fun startRecording(): Boolean {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) return false
        if (!running.compareAndSet(false, true)) return true
        val sampleRate = 16000
        val minBytes = AudioRecord.getMinBufferSize(sampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBytes <= 0) { running.set(false); return false }
        val bufferBytes = maxOf(minBytes, 4096)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes)
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release(); running.set(false); return false
        }
        recorder = rec
        rec.startRecording()
        Thread({
            val buffer = ByteArray(bufferBytes)
            while (running.get()) {
                val count = rec.read(buffer, 0, buffer.size)
                if (count > 0) onPcm16(buffer.copyOf(count), sampleRate, 1)
            }
        }, "Jarvis-AudioRecord").apply { isDaemon = true; start() }
        return true
    }

    fun stopRecording() {
        running.set(false)
        try { recorder?.stop() } catch (_: Exception) { }
        recorder?.release()
        recorder = null
    }

    fun isSpeechReady(): Boolean = ttsReady

    fun speak(text: String) {
        if (!ttsReady || text.isBlank()) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis-${System.nanoTime()}")
    }

    fun shutdown() {
        stopRecording()
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
    }
}
