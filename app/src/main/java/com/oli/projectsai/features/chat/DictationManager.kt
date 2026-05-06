package com.oli.projectsai.features.chat

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * UI-facing dictation state. Lives at the package level (not nested in [DictationManager]) so
 * Compose screens can pattern-match on it without dragging the manager class through the
 * import surface.
 */
sealed class DictationState {
    data object Idle : DictationState()
    /** Microphone open, audio streaming. [partialText] updates in real time as words are recognised. */
    data class Recording(val partialText: String = "") : DictationState()
    data object Transcribing : DictationState()
    data class Error(val message: String) : DictationState()
}

/**
 * Owns the [SpeechRecognizer] lifecycle for dictation in chat: starts/stops the recogniser,
 * exposes the current state and a normalised RMS level for the waveform, and parks the final
 * transcript until the UI consumes it.
 *
 * Constructed by [ChatViewModel] (one per chat session). Call [close] from the VM's
 * `onCleared()` so the recogniser doesn't leak across configuration changes.
 *
 * Not Hilt-managed: a `@Singleton` would share the recogniser across chats and a
 * `@ViewModelScoped` would still need an explicit `close()` from `onCleared`. Plain
 * construction keeps the contract obvious.
 */
class DictationManager(private val appContext: Context) {

    private val _state = MutableStateFlow<DictationState>(DictationState.Idle)
    val state: StateFlow<DictationState> = _state.asStateFlow()

    /** Normalised mic level 0..1, updated ~10 Hz while recording. Drives the waveform bars. */
    private val _rms = MutableStateFlow(0f)
    val rms: StateFlow<Float> = _rms.asStateFlow()

    private val _transcribed = MutableStateFlow<String?>(null)
    val transcribed: StateFlow<String?> = _transcribed.asStateFlow()

    private var recognizer: SpeechRecognizer? = null

    fun start() {
        if (_state.value != DictationState.Idle) return
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            _state.value = DictationState.Error("Speech recognition is not available on this device.")
            return
        }
        _transcribed.value = null

        val r = SpeechRecognizer.createSpeechRecognizer(appContext)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                _state.value = DictationState.Recording()
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {
                // onRmsChanged range is roughly -2..10; normalise to 0..1
                _rms.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                _state.value = DictationState.Transcribing
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                _state.value = DictationState.Recording(partialText = partial)
            }
            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                _rms.value = 0f
                if (text.isNotBlank()) {
                    _transcribed.value = text
                    _state.value = DictationState.Idle
                } else {
                    _state.value = DictationState.Error("Nothing was heard — try again.")
                }
                destroy()
            }
            override fun onError(error: Int) {
                _rms.value = 0f
                _state.value = DictationState.Error(
                    when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "Microphone error"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission needed"
                        SpeechRecognizer.ERROR_NO_MATCH -> "Could not understand — try speaking more clearly"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recogniser busy — try again"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected — try again"
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                        SpeechRecognizer.ERROR_SERVER ->
                            "Network error — enable offline speech in system Settings → General management → Language"
                        else -> "Recognition failed"
                    },
                )
                destroy()
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        r.startListening(intent)
    }

    /** Finishes the current utterance and waits for the final result. */
    fun stop() {
        recognizer?.stopListening()
    }

    fun cancel() {
        _rms.value = 0f
        destroy()
        _state.value = DictationState.Idle
    }

    /** Pulls the buffered transcript and clears it so the next dictation starts fresh. */
    fun consumeTranscribed(): String? {
        val text = _transcribed.value
        _transcribed.value = null
        return text
    }

    fun dismissError() {
        if (_state.value is DictationState.Error) {
            _state.value = DictationState.Idle
        }
    }

    fun close() {
        destroy()
    }

    private fun destroy() {
        recognizer?.destroy()
        recognizer = null
    }
}
