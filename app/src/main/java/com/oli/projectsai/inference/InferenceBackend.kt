package com.oli.projectsai.inference

import androidx.annotation.StringRes
import com.oli.projectsai.R
import kotlinx.coroutines.flow.Flow

data class ChatMessage(
    val role: String, // "user", "assistant", "system"
    val content: String,
    /** Raw image bytes attached to this turn (only honoured on the final user message). */
    val imageBytes: List<ByteArray> = emptyList()
)

data class GenerationConfig(
    // Thinking models (gemma4:26b, QwQ, DeepSeek-R1) can burn several thousand
    // tokens on chain-of-thought before emitting the answer. Ollama caps
    // num_predict to context length anyway, so 16000 leaves room for a long
    // think pass plus a substantial reply on complex prompts.
    val maxOutputTokens: Int = 16000,
    val temperature: Float = 0.7f,
    val topK: Int = 40,
    val topP: Float = 0.95f,
    val randomSeed: Int = 0,
    // When false the server skips prepending _REASONING_PREAMBLE. Useful for Quick
    // Actions that need short, direct responses and don't benefit from reasoning guidance.
    val applyDefaultPreamble: Boolean = true
)

enum class ModelPrecision(@StringRes val displayNameRes: Int, val estimatedRamGb: Float) {
    Q4(R.string.model_precision_q4, 4.5f),
    SFP8(R.string.model_precision_sfp8, 7.5f)
}

data class ModelInfo(
    val name: String,
    val precision: ModelPrecision,
    val filePath: String,
    val contextLength: Int = 16384
)

/** 16 kHz mono PCM 16-bit samples; Gemma 4 audio input is capped at this duration. */
const val TRANSCRIPTION_MAX_SECONDS = 30

sealed class InferenceError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    data object ModelNotLoaded : InferenceError("No model is loaded.")
    data object Cancelled : InferenceError("Generation cancelled.")
    class GenerationFailed(cause: Throwable) : InferenceError(
        cause.message ?: "Generation failed",
        cause
    )
    class TranscriptionFailed(cause: Throwable) : InferenceError(
        cause.message ?: "Transcription failed",
        cause
    )
    class LoadFailed(cause: Throwable) : InferenceError(
        cause.message ?: "Failed to load model",
        cause
    )
}

interface InferenceBackend {
    val id: String
    val displayName: String
    val isAvailable: Boolean
    val isLoaded: Boolean
    val loadedModel: ModelInfo?
    val supportsTranscription: Boolean
    val supportsVision: Boolean

    suspend fun loadModel(modelInfo: ModelInfo)
    suspend fun unloadModel()

    suspend fun generate(
        systemPrompt: String,
        messages: List<ChatMessage>,
        config: GenerationConfig = GenerationConfig()
    ): Flow<String>

    /**
     * Transcribes a PCM 16-bit mono 16 kHz audio buffer using the loaded model's audio encoder.
     * The buffer must be ≤ [TRANSCRIPTION_MAX_SECONDS] of audio.
     * Runs as a fresh Conversation so chat history is not polluted.
     *
     * [promptOverride] replaces the default transcription instruction — used by long-form
     * transcription to request speaker labels per chunk. Local backend honours it; remote
     * backends may ignore it.
     */
    suspend fun transcribe(pcm16MonoBytes: ByteArray, promptOverride: String? = null): String

    suspend fun countTokens(text: String): Int
}
