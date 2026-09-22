package dev.wristcontrol.wear.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the push-to-talk screen is doing right now. */
sealed interface VoiceState {
    data object Idle : VoiceState
    data class Listening(val partial: String, val amplitude: Float) : VoiceState
    data object Processing : VoiceState
    data class Result(val text: String) : VoiceState
    data class Error(val message: String, val recoverable: Boolean) : VoiceState
}

/**
 * Wrapper around [SpeechRecognizer] for the push-to-talk screen.
 *
 * Uses the streaming recognizer rather than [RecognizerIntent] because the
 * design calls for a live waveform, which needs RMS callbacks the intent-based
 * flow does not provide. Must be driven from the main thread — SpeechRecognizer
 * enforces this.
 */
class VoiceInputController(private val context: Context) {

    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private var recognizer: SpeechRecognizer? = null
    private var partial: String = ""

    val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        if (!isAvailable) {
            _state.value = VoiceState.Error("Speech recognition unavailable", recoverable = false)
            return
        }
        stop()
        partial = ""
        _state.value = VoiceState.Listening(partial = "", amplitude = 0f)

        val created = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(listener)
        }
        recognizer = created
        created.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                // Developers dictating a command pause mid-sentence to think.
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_500)
            }
        )
    }

    fun stop() {
        recognizer?.let {
            runCatching { it.stopListening() }
            runCatching { it.destroy() }
        }
        recognizer = null
    }

    fun reset() {
        stop()
        partial = ""
        _state.value = VoiceState.Idle
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _state.value = VoiceState.Listening(partial, 0f)
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) {
            // SpeechRecognizer reports roughly -2..10 dB; normalise for the waveform.
            val amplitude = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            val current = _state.value
            if (current is VoiceState.Listening) {
                _state.value = current.copy(amplitude = amplitude)
            }
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            _state.value = VoiceState.Processing
        }

        override fun onError(error: Int) {
            _state.value = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    VoiceState.Error("Didn't catch that", recoverable = true)

                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    VoiceState.Error("Microphone permission needed", recoverable = false)

                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                    VoiceState.Error("No network for speech", recoverable = true)

                else -> {
                    Log.d(TAG, "Recognition error $error")
                    VoiceState.Error("Speech error", recoverable = true)
                }
            }
            stop()
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            _state.value = if (text.isEmpty()) {
                VoiceState.Error("Didn't catch that", recoverable = true)
            } else {
                VoiceState.Result(text)
            }
            stop()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (text.isNotEmpty()) {
                partial = text
                val current = _state.value
                val amplitude = (current as? VoiceState.Listening)?.amplitude ?: 0f
                _state.value = VoiceState.Listening(partial, amplitude)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private companion object {
        const val TAG = "VoiceInputController"
    }
}
