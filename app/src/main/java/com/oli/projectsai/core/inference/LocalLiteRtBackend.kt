package com.oli.projectsai.core.inference

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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalLiteRtBackend @Inject constructor(
    @ApplicationContext private val context: Context
) : InferenceBackend {

    companion object {
        private const val TAG = "LocalLiteRT"
        private const val TRANSCRIBE_PROMPT =
            "Transcribe the audio verbatim. Output only the transcript text, no commentary."
        private const val DEFAULT_CHARS_PER_TOKEN = 4.0f
        // Older turns beyond this window are expected to be captured in accumulatedMemory.
        // Prevents prior_conversation from eating the entire context on long chats.
        private const val PRIOR_CONVERSATION_WINDOW = 20
    }

    override val id: String = "local_litertlm"
    override val displayName: String = "Local (LiteRT-LM)"
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

        val fullContext = buildFullContext(systemPrompt, messages.dropLast(1).takeLast(PRIOR_CONVERSATION_WINDOW))
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
            // Wrapped in XML so the model treats this as reference history rather than
            // active instructions to follow — bare text was causing imperatives typed by
            // the user in prior turns to be re-applied as standing rules.
            parts.add("<prior_conversation>\n$history\n</prior_conversation>")
        }
        return parts.joinToString("\n\n")
    }

    override suspend fun transcribe(
        pcm16MonoBytes: ByteArray,
        promptOverride: String?
    ): String = withContext(Dispatchers.IO) {
        val e = engine ?: throw InferenceError.ModelNotLoaded
        require(pcm16MonoBytes.isNotEmpty()) { "Empty audio buffer" }

        // LiteRT-LM 0.10's audio preprocessor uses miniaudio's ma_decoder, which
        // requires a container format (WAV/AIFF/MP3...) — passing raw PCM gets
        // "Failed to initialize miniaudio decoder". Wrap as a 16 kHz mono WAV.
        val wav = pcmToWav(pcm16MonoBytes, sampleRate = 16_000, channels = 1)

        try {
            e.createConversation(ConversationConfig()).use { conversation ->
                val response = conversation.sendMessage(
                    Contents.of(
                        Content.AudioBytes(wav),
                        Content.Text(promptOverride ?: TRANSCRIBE_PROMPT)
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

    /** Wraps raw 16-bit little-endian PCM in a minimal 44-byte WAV (RIFF/fmt/data) header. */
    private fun pcmToWav(pcm: ByteArray, sampleRate: Int, channels: Int): ByteArray {
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcm.size
        val out = ByteArray(44 + dataSize)
        val header = ByteBuffer.wrap(out, 0, 44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + dataSize)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)                          // fmt chunk size (PCM)
        header.putShort(1.toShort())               // audio format = PCM
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataSize)
        System.arraycopy(pcm, 0, out, 44, dataSize)
        return out
    }
}
