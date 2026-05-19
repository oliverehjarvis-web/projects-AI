package com.oli.projectsai.core.inference

import android.net.Uri
import com.oli.projectsai.core.preferences.VoiceSettings
import com.oli.projectsai.di.ApplicationScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

sealed class LongTranscriptionState {
    data object Idle : LongTranscriptionState()
    data class Decoding(val fileName: String) : LongTranscriptionState()
    data class Transcribing(
        val fileName: String,
        val totalChunks: Int,
        val completedChunks: Int,
        val partialTranscript: String,
        val elapsedSec: Int,
        val identifySpeakers: Boolean
    ) : LongTranscriptionState()
    data class Reconciling(val fileName: String, val partialTranscript: String) : LongTranscriptionState()
    data class Done(val transcript: String, val cancelled: Boolean = false) : LongTranscriptionState()
    data class Error(val message: String, val needsModel: Boolean = false) : LongTranscriptionState()
}

private fun LongTranscriptionState.isInFlight(): Boolean = when (this) {
    is LongTranscriptionState.Decoding,
    is LongTranscriptionState.Transcribing,
    is LongTranscriptionState.Reconciling -> true
    else -> false
}

/**
 * Owns the long-form transcription job at application scope so it survives the screen turning off
 * (the activity stops, the ViewModel can be cleared, but this singleton + its foreground service
 * keep the CPU awake via [TranscriptionForegroundService]'s WakeLock).
 *
 * Mirrors [GenerationController]: state lives here, the service observes [state] and stops itself
 * when the run finishes.
 */
@Singleton
class TranscriptionController @Inject constructor(
    private val inferenceManager: InferenceManager,
    private val audioDecoder: AudioDecoder,
    private val voiceSettings: VoiceSettings,
    @ApplicationScope private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow<LongTranscriptionState>(LongTranscriptionState.Idle)
    val state: StateFlow<LongTranscriptionState> = _state.asStateFlow()

    private var job: Job? = null

    val isActive: Boolean get() = _state.value.isInFlight()

    fun start(uri: Uri, fileName: String, identifySpeakers: Boolean): Boolean {
        val claimed = synchronized(this) {
            if (_state.value.isInFlight()) false
            else {
                _state.value = LongTranscriptionState.Decoding(fileName)
                true
            }
        }
        if (!claimed) return false
        job = scope.launch { runJob(uri, fileName, identifySpeakers) }
        return true
    }

    fun cancel() {
        job?.cancel(CancellationException("User cancelled long-form transcription"))
    }

    fun reset() {
        job?.cancel()
        job = null
        _state.value = LongTranscriptionState.Idle
    }

    private suspend fun runJob(uri: Uri, fileName: String, identifySpeakers: Boolean) {
        if (!inferenceManager.localBackendReady) {
            val voiceModel = resolveVoiceModel()
            if (voiceModel == null) {
                _state.value = LongTranscriptionState.Error(
                    "Pick a voice transcription model in Settings → Voice transcription.",
                    needsModel = true
                )
                return
            }
            try {
                inferenceManager.prepareLocalForTranscription(voiceModel)
            } catch (t: Throwable) {
                _state.value = LongTranscriptionState.Error(t.message ?: "Failed to load voice model")
                return
            }
        }

        val pcm = try {
            audioDecoder.decodeToPcm16Mono(uri)
        } catch (t: Throwable) {
            _state.value = LongTranscriptionState.Error(t.message ?: "Could not decode audio")
            return
        }

        val chunks = audioDecoder.chunkPcm16Mono(pcm)
        if (chunks.isEmpty()) {
            _state.value = LongTranscriptionState.Error("Decoded audio was empty.")
            return
        }

        val startedAt = System.currentTimeMillis()
        val perChunkText = mutableListOf<String>()
        val partial = StringBuilder()

        val chunkPromptOverride = if (identifySpeakers)
            SummarisationPrompts.buildDiarizedTranscriptionHint() else null

        try {
            chunks.forEachIndexed { index, chunk ->
                _state.value = LongTranscriptionState.Transcribing(
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

                _state.value = LongTranscriptionState.Transcribing(
                    fileName = fileName,
                    totalChunks = chunks.size,
                    completedChunks = index + 1,
                    partialTranscript = partial.toString(),
                    elapsedSec = ((System.currentTimeMillis() - startedAt) / 1000).toInt(),
                    identifySpeakers = identifySpeakers
                )
            }
        } catch (ce: CancellationException) {
            _state.value = LongTranscriptionState.Done(partial.toString().trim(), cancelled = true)
            return
        }

        if (identifySpeakers && partial.isNotBlank()) {
            _state.value = LongTranscriptionState.Reconciling(fileName, partial.toString())
            val (sys, usr) = SummarisationPrompts.buildSpeakerReconcilePrompt(partial.toString())
            val preferred = listOfNotNull(
                "remote_http".takeIf { inferenceManager.getBackend(it)?.isLoaded == true },
                inferenceManager.getActiveBackend()?.id
            ).distinct()
            var reconciled: String? = null
            for (backendId in preferred) {
                try {
                    val out = StringBuilder()
                    inferenceManager.generate(
                        systemPrompt = sys,
                        messages = listOf(ChatMessage(role = "user", content = usr)),
                        config = GenerationConfig(applyDefaultPreamble = false),
                        backendId = backendId
                    ).collect { token -> out.append(token) }
                    reconciled = out.toString().trim()
                    break
                } catch (ce: CancellationException) {
                    _state.value = LongTranscriptionState.Done(partial.toString().trim(), cancelled = true)
                    return
                } catch (_: Throwable) {
                    // Try the next candidate; fall back to raw transcript below if none work.
                }
            }
            _state.value = LongTranscriptionState.Done(reconciled ?: partial.toString().trim())
            return
        }

        _state.value = LongTranscriptionState.Done(partial.toString().trim())
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
}
