package com.oli.projectsai.ui.transcription

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oli.projectsai.data.db.entity.Chat
import com.oli.projectsai.data.db.entity.Message
import com.oli.projectsai.data.db.entity.MessageRole
import com.oli.projectsai.data.db.entity.Project
import com.oli.projectsai.data.preferences.VoiceSettings
import com.oli.projectsai.data.repository.ChatRepository
import com.oli.projectsai.data.repository.ProjectRepository
import com.oli.projectsai.inference.AudioDecoder
import com.oli.projectsai.inference.ChatMessage
import com.oli.projectsai.inference.GenerationConfig
import com.oli.projectsai.inference.InferenceManager
import com.oli.projectsai.inference.ModelInfo
import com.oli.projectsai.inference.ModelPrecision
import com.oli.projectsai.inference.SummarisationPrompts
import com.oli.projectsai.ui.common.copyToClipboard
import com.oli.projectsai.ui.common.shareText
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class LongFormTranscriptionViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val inferenceManager: InferenceManager,
    private val audioDecoder: AudioDecoder,
    private val chatRepository: ChatRepository,
    private val projectRepository: ProjectRepository,
    private val voiceSettings: VoiceSettings
) : ViewModel() {

    sealed class State {
        data object Idle : State()
        data class Decoding(val fileName: String) : State()
        data class Transcribing(
            val fileName: String,
            val totalChunks: Int,
            val completedChunks: Int,
            val partialTranscript: String,
            val elapsedSec: Int,
            val identifySpeakers: Boolean
        ) : State()
        data class Reconciling(val fileName: String, val partialTranscript: String) : State()
        data class Done(val transcript: String, val cancelled: Boolean = false) : State()
        data class Error(val message: String, val needsModel: Boolean = false) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    val projects: StateFlow<List<Project>> = projectRepository.getAllProjects()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var job: Job? = null

    fun start(uri: Uri, fileName: String, identifySpeakers: Boolean) {
        if (job?.isActive == true) return
        job = viewModelScope.launch { runJob(uri, fileName, identifySpeakers) }
    }

    fun cancel() {
        job?.cancel(CancellationException("User cancelled long-form transcription"))
    }

    private suspend fun runJob(uri: Uri, fileName: String, identifySpeakers: Boolean) {
        if (!inferenceManager.localBackendReady) {
            val voiceModel = resolveVoiceModel()
            if (voiceModel == null) {
                _state.value = State.Error(
                    "Pick a voice transcription model in Settings → Voice transcription.",
                    needsModel = true
                )
                return
            }
            _state.value = State.Decoding(fileName)
            try {
                inferenceManager.prepareLocalForTranscription(voiceModel)
            } catch (t: Throwable) {
                _state.value = State.Error(t.message ?: "Failed to load voice model")
                return
            }
        }

        _state.value = State.Decoding(fileName)
        val pcm = try {
            audioDecoder.decodeToPcm16Mono(uri)
        } catch (t: Throwable) {
            _state.value = State.Error(t.message ?: "Could not decode audio")
            return
        }

        val chunks = audioDecoder.chunkPcm16Mono(pcm)
        if (chunks.isEmpty()) {
            _state.value = State.Error("Decoded audio was empty.")
            return
        }

        val startedAt = System.currentTimeMillis()
        val perChunkText = mutableListOf<String>()
        val partial = StringBuilder()

        val chunkPromptOverride = if (identifySpeakers)
            SummarisationPrompts.buildDiarizedTranscriptionHint() else null

        try {
            chunks.forEachIndexed { index, chunk ->
                _state.value = State.Transcribing(
                    fileName = fileName,
                    totalChunks = chunks.size,
                    completedChunks = index,
                    partialTranscript = partial.toString(),
                    elapsedSec = ((System.currentTimeMillis() - startedAt) / 1000).toInt(),
                    identifySpeakers = identifySpeakers
                )
                val raw = try {
                    inferenceManager.transcribeViaLocal(chunk, promptOverride = chunkPromptOverride)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    "[chunk ${index + 1} failed: ${t.message ?: "unknown error"}]"
                }
                val piece = stitch(perChunkText.lastOrNull(), raw.trim())
                perChunkText += raw.trim()
                if (partial.isNotEmpty()) partial.append("\n\n")
                partial.append(piece)

                _state.value = State.Transcribing(
                    fileName = fileName,
                    totalChunks = chunks.size,
                    completedChunks = index + 1,
                    partialTranscript = partial.toString(),
                    elapsedSec = ((System.currentTimeMillis() - startedAt) / 1000).toInt(),
                    identifySpeakers = identifySpeakers
                )
            }
        } catch (ce: CancellationException) {
            _state.value = State.Done(partial.toString().trim(), cancelled = true)
            return
        }

        if (identifySpeakers && partial.isNotBlank()) {
            _state.value = State.Reconciling(fileName, partial.toString())
            try {
                val (sys, usr) = SummarisationPrompts.buildSpeakerReconcilePrompt(partial.toString())
                val out = StringBuilder()
                inferenceManager.generate(
                    systemPrompt = sys,
                    messages = listOf(ChatMessage(role = "user", content = usr)),
                    config = GenerationConfig(applyDefaultPreamble = false)
                ).collect { token -> out.append(token) }
                _state.value = State.Done(out.toString().trim())
                return
            } catch (ce: CancellationException) {
                _state.value = State.Done(partial.toString().trim(), cancelled = true)
                return
            } catch (t: Throwable) {
                // Reconcile is best-effort: if it fails, fall back to the raw stitched transcript.
                _state.value = State.Done(partial.toString().trim())
                return
            }
        }

        _state.value = State.Done(partial.toString().trim())
    }

    /**
     * Drops a leading sentence from [next] when it duplicates the tail of [previous]. The 1 s
     * overlap between chunks frequently produces a repeated sentence at the boundary; this
     * keeps it from showing up twice in the final transcript.
     */
    private fun stitch(previous: String?, next: String): String {
        if (previous.isNullOrBlank() || next.isBlank()) return next
        val prevTail = previous.takeLast(80).lowercase()
        val nextSentenceEnd = next.indexOfAny(charArrayOf('.', '?', '!'))
        if (nextSentenceEnd <= 0 || nextSentenceEnd > 120) return next
        val firstSentence = next.substring(0, nextSentenceEnd + 1).trim().lowercase()
        if (firstSentence.length < 10) return next
        val key = firstSentence.take(20)
        return if (prevTail.contains(key)) next.substring(nextSentenceEnd + 1).trimStart() else next
    }

    fun sendToChat(projectId: Long, transcript: String, onReady: (Long) -> Unit) {
        viewModelScope.launch {
            val title = "Transcript: ${java.time.LocalDate.now()}"
            val chatId = chatRepository.createChat(Chat(projectId = projectId, title = title))
            chatRepository.addMessage(
                Message(
                    chatId = chatId,
                    role = MessageRole.USER,
                    content = transcript,
                    tokenCount = inferenceManager.countTokens(transcript)
                )
            )
            onReady(chatId)
        }
    }

    fun reset() {
        job?.cancel()
        job = null
        _state.value = State.Idle
    }

    fun copyToClipboard(text: String) = context.copyToClipboard(text, label = "Transcript")
    fun shareText(text: String) = context.shareText(text, chooserTitle = "Share transcript")

    private suspend fun resolveVoiceModel(): ModelInfo? {
        val path = voiceSettings.voiceModelPath.first()
        if (path.isBlank() || !File(path).exists()) return null
        val file = File(path)
        return ModelInfo(
            name = file.nameWithoutExtension,
            precision = ModelPrecision.Q4,
            filePath = path
        )
    }

    override fun onCleared() {
        job?.cancel()
        super.onCleared()
    }
}
