package com.oli.projectsai.ui.transcription

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oli.projectsai.data.preferences.VoiceSettings
import com.oli.projectsai.inference.AudioCapture
import com.oli.projectsai.inference.InferenceManager
import com.oli.projectsai.inference.ModelInfo
import com.oli.projectsai.inference.ModelPrecision
import com.oli.projectsai.inference.TRANSCRIPTION_MAX_SECONDS
import com.oli.projectsai.ui.common.copyToClipboard
import com.oli.projectsai.ui.common.shareText
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class TranscriptionViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val inferenceManager: InferenceManager,
    private val audioCapture: AudioCapture,
    private val voiceSettings: VoiceSettings
) : ViewModel() {

    sealed class RecordingState {
        data object Idle : RecordingState()
        data object PreparingModel : RecordingState()
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
        if (inferenceManager.localBackendReady) {
            beginRecording()
            return
        }
        // Lazy-load the on-device voice model on first mic press in this session.
        viewModelScope.launch {
            val path = voiceSettings.voiceModelPath.first()
            if (path.isBlank() || !File(path).exists()) {
                _state.value = RecordingState.Error(
                    "Pick a voice transcription model in Settings → Voice transcription.",
                    needsModel = true
                )
                return@launch
            }
            _state.value = RecordingState.PreparingModel
            try {
                inferenceManager.prepareLocalForTranscription(voiceModelFor(path))
            } catch (t: Throwable) {
                _state.value = RecordingState.Error(t.message ?: "Failed to load voice model")
                return@launch
            }
            beginRecording()
        }
    }

    private fun beginRecording() {
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
                val text = inferenceManager.transcribeViaLocal(pcm)
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

    fun copyToClipboard(text: String) = context.copyToClipboard(text, label = "Transcript")

    fun shareText(text: String) = context.shareText(text, chooserTitle = "Share transcript")

    override fun onCleared() {
        tickJob?.cancel()
        audioCapture.cancel()
        super.onCleared()
    }

    private fun voiceModelFor(path: String): ModelInfo {
        val file = File(path)
        return ModelInfo(
            name = file.nameWithoutExtension,
            precision = ModelPrecision.Q4,
            filePath = path
        )
    }
}
