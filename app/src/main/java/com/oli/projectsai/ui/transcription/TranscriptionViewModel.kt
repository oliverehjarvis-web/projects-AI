package com.oli.projectsai.ui.transcription

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oli.projectsai.inference.AudioCapture
import com.oli.projectsai.inference.InferenceManager
import com.oli.projectsai.inference.ModelState
import com.oli.projectsai.inference.TRANSCRIPTION_MAX_SECONDS
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TranscriptionViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val inferenceManager: InferenceManager,
    private val audioCapture: AudioCapture
) : ViewModel() {

    sealed class RecordingState {
        data object Idle : RecordingState()
        data class Recording(val elapsedMs: Long) : RecordingState()
        data object Transcribing : RecordingState()
        data class Done(val text: String) : RecordingState()
        data class Error(val message: String, val needsModel: Boolean = false) : RecordingState()
    }

    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private var tickJob: Job? = null
    private var startedAt: Long = 0L

    fun start() {
        if (inferenceManager.modelState.value !is ModelState.Loaded) {
            _state.value = RecordingState.Error(
                "Load a model before transcribing.",
                needsModel = true
            )
            return
        }
        try {
            audioCapture.start()
        } catch (t: Throwable) {
            _state.value = RecordingState.Error(t.message ?: "Failed to start recording")
            return
        }
        startedAt = System.currentTimeMillis()
        _state.value = RecordingState.Recording(0L)
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            while (audioCapture.isRecording) {
                val elapsed = System.currentTimeMillis() - startedAt
                _state.value = RecordingState.Recording(elapsed)
                if (elapsed >= TRANSCRIPTION_MAX_SECONDS * 1000L) {
                    stop()
                    return@launch
                }
                delay(250)
            }
        }
    }

    fun stop() {
        if (_state.value !is RecordingState.Recording) {
            audioCapture.cancel()
            return
        }
        tickJob?.cancel()
        tickJob = null
        val pcm = audioCapture.stop()
        if (pcm.isEmpty()) {
            _state.value = RecordingState.Error("No audio captured.")
            return
        }
        _state.value = RecordingState.Transcribing
        viewModelScope.launch {
            try {
                val text = inferenceManager.transcribe(pcm)
                _state.value = if (text.isBlank()) {
                    RecordingState.Error("Transcription was empty — try speaking closer to the mic.")
                } else {
                    RecordingState.Done(text)
                }
            } catch (t: Throwable) {
                _state.value = RecordingState.Error(t.message ?: "Transcription failed")
            }
        }
    }

    fun reset() {
        tickJob?.cancel()
        tickJob = null
        audioCapture.cancel()
        _state.value = RecordingState.Idle
    }

    fun copyToClipboard(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Transcript", text))
    }

    fun shareText(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Share transcript").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    override fun onCleared() {
        tickJob?.cancel()
        audioCapture.cancel()
        super.onCleared()
    }
}
