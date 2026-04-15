package com.oli.projectsai.inference

import kotlinx.coroutines.flow.Flow

data class ChatMessage(
    val role: String, // "user", "model", "system"
    val content: String
)

data class GenerationConfig(
    val maxOutputTokens: Int = 2048,
    val temperature: Float = 0.7f,
    val topK: Int = 40,
    val topP: Float = 0.95f,
    val randomSeed: Int = 0
)

enum class ModelPrecision(val displayName: String, val estimatedRamGb: Float) {
    Q4("Q4 (4-bit quantised)", 4.5f),
    SFP8("SFP8 (8-bit)", 7.5f)
}

data class ModelInfo(
    val name: String,
    val precision: ModelPrecision,
    val filePath: String,
    val contextLength: Int = 8192
)

/** 16 kHz mono PCM 16-bit samples; Gemma 4 audio input is capped at this duration. */
const val TRANSCRIPTION_MAX_SECONDS = 30

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
