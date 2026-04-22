package com.oli.projectsai.inference

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalMediaPipeBackend @Inject constructor(
    @ApplicationContext private val context: Context
) : InferenceBackend {

    companion object {
        private const val TAG = "LocalLiteRT"
        private const val TRANSCRIBE_PROMPT =
            "Transcribe the audio verbatim. Output only the transcript text, no commentary."
        private const val DEFAULT_CHARS_PER_TOKEN = 4.0f
    }

    override val id: String = "local_mediapipe"
    override val displayName: String = "Local (LiteRT)"
    override val isAvailable: Boolean = true
    override val supportsTranscription: Boolean = true
    override val supportsVision: Boolean = true

    private var engine: Engine? = null
    private var _loadedModel: ModelInfo? = null
    // Approximate ratio — LiteRT-LM 0.10 does not expose a tokenizer. Hard-coded to the Gemma
    // SentencePiece English average; accurate to within ~10% for budgeting purposes.
    private val charsPerToken: Float = DEFAULT_CHARS_PER_TOKEN

    override val isLoaded: Boolean get() = engine != null
    override val loadedModel: ModelInfo? get() = _loadedModel

    override suspend fun loadModel(modelInfo: ModelInfo) {
        withContext(Dispatchers.IO) {
            unloadModel()
            // CPU-only: OnePlus devices don't expose libOpenCL.so at the path LiteRT-LM 0.10
            // searches, and even the warm-up probe hard-crashes natively before the fallback
            // can kick in. An 8 Gen 4 on CPU is fast enough for 4B Q4 in practice.
            val engineConfig = EngineConfig(
                modelPath = modelInfo.filePath,
                backend = Backend.CPU(),
                audioBackend = Backend.CPU(),
                visionBackend = Backend.CPU(),
                maxNumTokens = modelInfo.contextLength,
                cacheDir = null
            )
            engine = try {
                Engine(engineConfig).also { it.initialize() }
            } catch (t: Throwable) {
                Log.e(TAG, "Engine init failed: ${t.message}")
                throw InferenceError.LoadFailed(t)
            }
            _loadedModel = modelInfo
            Log.i(TAG, "Model loaded (CPU): ${modelInfo.name}")
        }
    }

    override suspend fun unloadModel() {
        withContext(Dispatchers.IO) {
            engine?.close()
        }
        engine = null
        _loadedModel = null
    }

    override suspend fun generate(
        systemPrompt: String,
        messages: List<ChatMessage>,
        config: GenerationConfig
    ): Flow<String> {
        val e = engine ?: throw InferenceError.ModelNotLoaded

        val fullContext = buildFullContext(systemPrompt, messages.dropLast(1))
        val systemInstruction: Contents? = if (fullContext.isNotBlank()) Contents.of(fullContext) else null
        val conversationConfig = ConversationConfig(systemInstruction = systemInstruction)

        return flow {
            try {
                e.createConversation(conversationConfig).use { conversation ->
                    val lastUser = messages.lastOrNull { it.role == "user" }
                        ?: throw InferenceError.GenerationFailed(
                            IllegalArgumentException("No user message to respond to")
                        )

                    val turnContents = buildList<Content> {
                        lastUser.imageBytes.forEach { add(Content.ImageBytes(it)) }
                        add(Content.Text(lastUser.content))
                    }

                    conversation.sendMessageAsync(Contents.of(*turnContents.toTypedArray()))
                        .collect { message ->
                            val chunk = message.contents.contents
                                .filterIsInstance<Content.Text>()
                                .joinToString("") { it.text }
                            if (chunk.isNotEmpty()) emit(chunk)
                        }
                }
            } catch (ie: InferenceError) {
                throw ie
            } catch (t: Throwable) {
                throw InferenceError.GenerationFailed(t)
            }
        }.flowOn(Dispatchers.IO)
    }

    /**
     * Combines the project system prompt with prior conversation turns into a single
     * context string for ConversationConfig.systemInstruction.
     */
    private fun buildFullContext(systemPrompt: String, priorMessages: List<ChatMessage>): String {
        val parts = mutableListOf<String>()
        if (systemPrompt.isNotBlank()) {
            parts.add(systemPrompt)
        }
        if (priorMessages.isNotEmpty()) {
            val history = priorMessages.joinToString("\n") { msg ->
                val label = if (msg.role == "user") "User" else "Assistant"
                "$label: ${msg.content}"
            }
            parts.add("Prior conversation:\n$history")
        }
        return parts.joinToString("\n\n")
    }

    override suspend fun transcribe(pcm16MonoBytes: ByteArray): String = withContext(Dispatchers.IO) {
        val e = engine ?: throw InferenceError.ModelNotLoaded
        require(pcm16MonoBytes.isNotEmpty()) { "Empty audio buffer" }

        try {
            e.createConversation(ConversationConfig()).use { conversation ->
                val response = conversation.sendMessage(
                    Contents.of(
                        Content.AudioBytes(pcm16MonoBytes),
                        Content.Text(TRANSCRIBE_PROMPT)
                    )
                )
                response.contents.contents
                    .filterIsInstance<Content.Text>()
                    .joinToString("") { it.text }
                    .trim()
            }
        } catch (ie: InferenceError) {
            throw ie
        } catch (t: Throwable) {
            throw InferenceError.TranscriptionFailed(t)
        }
    }

    override suspend fun countTokens(text: String): Int = withContext(Dispatchers.Default) {
        if (text.isEmpty()) 0
        else (text.length / charsPerToken).toInt().coerceAtLeast(1)
    }
}
