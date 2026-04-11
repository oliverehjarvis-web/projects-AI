package com.oli.projectsai.inference

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalMediaPipeBackend @Inject constructor(
    @ApplicationContext private val context: Context
) : InferenceBackend {

    companion object {
        private const val TAG = "LocalMediaPipe"
    }

    override val id: String = "local_mediapipe"
    override val displayName: String = "Local (MediaPipe)"
    override val isAvailable: Boolean = true

    private var inference: LlmInference? = null
    private var _loadedModel: ModelInfo? = null

    override val isLoaded: Boolean get() = inference != null
    override val loadedModel: ModelInfo? get() = _loadedModel

    override suspend fun loadModel(modelInfo: ModelInfo) = withContext(Dispatchers.IO) {
        unloadModel()
        try {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelInfo.filePath)
                .setMaxTokens(modelInfo.contextLength)
                .setResultListener { partialResult, done ->
                    // handled per-request via generateResponse
                }
                .build()

            inference = LlmInference.createFromOptions(context, options)
            _loadedModel = modelInfo
            Log.i(TAG, "Model loaded: ${modelInfo.name} (${modelInfo.precision.displayName})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model", e)
            inference = null
            _loadedModel = null
            throw e
        }
    }

    override suspend fun unloadModel() {
        inference?.close()
        inference = null
        _loadedModel = null
    }

    override suspend fun generate(
        systemPrompt: String,
        messages: List<ChatMessage>,
        config: GenerationConfig
    ): Flow<String> = callbackFlow {
        val llm = inference ?: throw IllegalStateException("Model not loaded")

        val prompt = buildPrompt(systemPrompt, messages)

        withContext(Dispatchers.IO) {
            try {
                // Use streaming generation
                llm.generateResponseAsync(prompt).let { /* fire */ }
            } catch (e: Exception) {
                close(e)
            }
        }

        // For MediaPipe LLM Inference, we use the synchronous API
        // wrapped in a coroutine since the async callback API varies by version.
        // This provides a clean upgrade path.
        withContext(Dispatchers.IO) {
            try {
                val result = llm.generateResponse(prompt)
                trySend(result)
                close()
            } catch (e: Exception) {
                close(e)
            }
        }

        awaitClose()
    }

    private fun buildPrompt(systemPrompt: String, messages: List<ChatMessage>): String {
        // Gemma 4 chat template format
        val sb = StringBuilder()

        if (systemPrompt.isNotBlank()) {
            sb.append("<start_of_turn>system\n")
            sb.append(systemPrompt)
            sb.append("<end_of_turn>\n")
        }

        for (msg in messages) {
            val role = when (msg.role) {
                "user" -> "user"
                "model", "assistant" -> "model"
                else -> msg.role
            }
            sb.append("<start_of_turn>$role\n")
            sb.append(msg.content)
            sb.append("<end_of_turn>\n")
        }

        // Signal the model to generate
        sb.append("<start_of_turn>model\n")
        return sb.toString()
    }

    override suspend fun countTokens(text: String): Int {
        // MediaPipe doesn't expose a public tokeniser count.
        // Use a reasonable approximation: ~3.5 chars per token for English.
        // This will be replaced with actual tokeniser calls when the API supports it.
        return (text.length / 3.5).toInt().coerceAtLeast(1)
    }
}
