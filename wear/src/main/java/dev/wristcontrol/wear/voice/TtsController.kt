package dev.wristcontrol.wear.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Text-to-speech for agent updates and approval announcements.
 *
 * Engine init is asynchronous and can take a second on a cold watch, so calls
 * made before it is ready are held in [pending] rather than dropped — the first
 * thing the user hears is usually the most important one ("Claude requests
 * permission to...").
 */
class TtsController(context: Context) {

    private val ready = AtomicBoolean(false)
    private var pending: Pair<String, Boolean>? = null

    private val engine = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            ready.set(true)
            pending?.let { (text, interrupt) -> speak(text, interrupt) }
            pending = null
        }
    }.apply {
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = Unit
            @Deprecated("Required by the framework base class")
            override fun onError(utteranceId: String?) = Unit
        })
    }

    fun speak(text: String, interrupt: Boolean = false) {
        if (text.isBlank()) return
        if (!ready.get()) {
            pending = text to interrupt
            return
        }
        runCatching { engine.language = Locale.getDefault() }
        engine.speak(
            text.take(MAX_SPOKEN_CHARS),
            if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
            null,
            "wristcontrol-${System.currentTimeMillis()}",
        )
    }

    fun stop() {
        if (ready.get()) engine.stop()
        pending = null
    }

    fun shutdown() {
        runCatching { engine.stop() }
        runCatching { engine.shutdown() }
        ready.set(false)
    }

    private companion object {
        /** Nobody wants a watch reading a 400-line diff aloud. */
        const val MAX_SPOKEN_CHARS = 280
    }
}
