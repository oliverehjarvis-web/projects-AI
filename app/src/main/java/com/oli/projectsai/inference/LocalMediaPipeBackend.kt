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
    }

    override val id: String = "local_mediapipe"
    override val displayName: String = "Local (LiteRT)"
    override val isAvailable: Boolean = true

    private var engine: Engine? = null
    private var _loadedModel: ModelInfo? = null

    override val isLoaded: Boolean get() = engine != null
    override val loadedModel: ModelInfo? get() = _loadedModel

    override suspend fun loadModel(modelInfo: ModelInfo) {
        withContext(Dispatchers.IO) {
            unloadModel()
            try {
                val engineConfig = EngineConfig(
                    modelPath = modelInfo.filePath,
                    backend = Backend.CPU()
                )
                val e = Engine(engineConfig)
                e.initialize()
                engine = e
                _loadedModel = modelInfo
                Log.i(TAG, "Model loaded: ${modelInfo.name}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load model", e)
                engine = null
                _loadedModel = null
                throw e
            }
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
        val e = engine ?: throw IllegalStateException("Model not loaded")

        // Build a combined system instruction: project context + prior conversation turns.
        // Passed via ConversationConfig so the model has full context before each reply.
        val fullContext = buildFullContext(systemPrompt, messages.dropLast(1))
        val systemInstruction: Contents? = if (fullContext.isNotBlank()) Contents.of(fullContext) else null
        val conversationConfig = ConversationConfig(systemInstruction = systemInstruction)

        return flow {
            e.createConversation(conversationConfig).use { conversation ->
                val lastUserMessage = messages.lastOrNull { it.role == "user" }?.content
                    ?: throw IllegalArgumentException("No user message to respond to")

                // sendMessageAsync streams incremental tokens as Flow<Message>.
                // Text is extracted from the Content.Text items in each message's contents list.
                conversation.sendMessageAsync(lastUserMessage)
                    .collect { message ->
                        val chunk = message.contents.contents
                            .filterIsInstance<Content.Text>()
                            .joinToString("") { it.text }
                        if (chunk.isNotEmpty()) emit(chunk)
                    }
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

    override suspend fun countTokens(text: String): Int {
        return (text.length / 3.5).toInt().coerceAtLeast(1)
    }
}
