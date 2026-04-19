package com.oli.projectsai.inference

import androidx.annotation.StringRes
import com.oli.projectsai.R
import kotlinx.coroutines.flow.Flow

data class ChatMessage(
    val role: String, // "user", "model", "system"
    val content: String,
    /** Raw image bytes attached to this turn (only honoured on the final user message). */
    val imageBytes: List<ByteArray> = emptyList()
)

data class GenerationConfig(
    val maxOutputTokens: Int = 2048,
    val temperature: Float = 0.7f,
    val topK: Int = 40,
    val topP: Float = 0.95f,
    val randomSeed: Int = 0
)

enum class ModelPrecision(@StringRes val displayNameRes: Int, val estimatedRamGb: Float) {
    Q4(R.string.model_precision_q4, 4.5f),
    SFP8(R.string.model_precision_sfp8, 7.5f)
}

data class ModelInfo(
    val name: String,
    val precision: ModelPrecision,
    val filePath: String,
    val contextLength: Int = 4096
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
     */
    suspend fun transcribe(pcm16MonoBytes: ByteArray): String

    suspend fun countTokens(text: String): Int
}
