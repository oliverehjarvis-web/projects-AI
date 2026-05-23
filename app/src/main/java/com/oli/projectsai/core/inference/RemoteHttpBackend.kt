package com.oli.projectsai.core.inference

import android.util.Base64
import com.oli.projectsai.core.preferences.RemoteSettings
import com.oli.projectsai.di.ApplicationScope
import com.oli.projectsai.core.net.HttpClient
import com.oli.projectsai.core.net.HttpError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.transform
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RemoteHttpBackend @Inject constructor(
    private val remoteSettings: RemoteSettings,
    private val httpClient: HttpClient,
    @ApplicationScope private val scope: CoroutineScope
) : InferenceBackend {

    companion object {
        // SSE idle timeout: generous to accommodate cold starts on large models, long prompt
        // processing, and slower hardware. Ten minutes of silence before we assume the stream has
        // hung — first-token latency is the usual offender, tokens then flow every few hundred ms.
        private const val STREAM_IDLE_TIMEOUT_MS = 600_000
        // Weight given to each fresh server measurement when smoothing the chars/token estimate.
        private const val CALIBRATION_ALPHA = 0.3f
    }

    // Cached for the synchronous `isAvailable` property; the suspend paths read freshly.
    @Volatile private var serverUrlSnapshot: String = ""
    @Volatile private var _isLoaded: Boolean = false
    // Exact usage from the most recent generation's `usage` event; consumed once by the caller.
    @Volatile private var _lastUsage: GenerationUsage? = null
    // Character length of the last prompt we sent, paired with the server's reported prompt-token
    // count to calibrate [calibratedCharsPerToken] so future estimates track this model/tokenizer.
    @Volatile private var lastPromptChars: Int = 0
    @Volatile private var calibratedCharsPerToken: Float = ContextBudget.DEFAULT_CHARS_PER_TOKEN

    init {
        remoteSettings.serverUrl.onEach { serverUrlSnapshot = it }.launchIn(scope)
    }

    override val id: String = "remote_http"
    override val displayName: String = "Remote Server"
    override val isAvailable: Boolean get() = serverUrlSnapshot.isNotBlank()
    override val isLoaded: Boolean get() = _isLoaded
    override val loadedModel: ModelInfo? = null
    override val supportsTranscription: Boolean = false
    override val supportsVision: Boolean = true

    override suspend fun loadModel(modelInfo: ModelInfo) {
        val url = remoteSettings.serverUrl.first().ifBlank {
            throw InferenceError.LoadFailed(IllegalStateException("Server URL not configured"))
        }
        val token = remoteSettings.apiToken.first()
        try {
            httpClient.get(
                url = "$url/v1/health",
                bearer = token,
                connectTimeoutMs = 10_000,
                readTimeoutMs = 10_000
            )
        } catch (e: HttpError.Status) {
            throw InferenceError.LoadFailed(
                IllegalStateException("Health check failed: HTTP ${e.code}${formatErrorBody(e.body)}")
            )
        } catch (e: HttpError) {
            throw InferenceError.LoadFailed(e)
        }
        _isLoaded = true
    }

    override suspend fun unloadModel() {
        _isLoaded = false
    }

    override suspend fun generate(
        systemPrompt: String,
        messages: List<ChatMessage>,
        config: GenerationConfig
    ): Flow<String> {
        val url = remoteSettings.serverUrl.first().ifBlank {
            throw InferenceError.GenerationFailed(
                IllegalStateException("Remote server URL not configured.")
            )
        }
        val token = remoteSettings.apiToken.first()
        val model = remoteSettings.defaultModel.first()
        if (model.isBlank()) {
            throw InferenceError.GenerationFailed(
                IllegalStateException("No remote model selected. Pick one in Settings → Remote server.")
            )
        }

        // Track what we sent so the server's prompt_eval_count can calibrate our estimate.
        lastPromptChars = systemPrompt.length + messages.sumOf { it.content.length }

        val body = JSONObject().apply {
            put("system_prompt", systemPrompt)
            // Tell the server the client has already folded global context into
            // `system_prompt`, so it should skip the auto-lookup that would
            // otherwise double-emit the user's name and rules.
            put("user_name", "")
            put("global_rules", "")
            put("messages", JSONArray().apply {
                messages.forEach { m ->
                    put(JSONObject().apply {
                        put("role", m.role)
                        put("content", m.content)
                        if (m.imageBytes.isNotEmpty()) {
                            put("images", JSONArray().apply {
                                m.imageBytes.forEach { bytes ->
                                    put(Base64.encodeToString(bytes, Base64.NO_WRAP))
                                }
                            })
                        }
                    })
                }
            })
            put("config", JSONObject().apply {
                put("model", model)
                put("max_tokens", config.maxOutputTokens)
                put("temperature", config.temperature.toDouble())
                put("top_p", config.topP.toDouble())
                put("apply_default_preamble", config.applyDefaultPreamble)
                config.numCtx?.let { put("num_ctx", it) }
            })
        }.toString()

        return httpClient.streamLines(
            url = "$url/v1/generate",
            method = "POST",
            body = body,
            bearer = token,
            connectTimeoutMs = 15_000,
            readTimeoutMs = STREAM_IDLE_TIMEOUT_MS,
            headers = mapOf("Accept" to "text/event-stream")
        )
            .takeWhile { line -> line.removePrefix("data: ") != "[DONE]" }
            .transform { line ->
                if (!line.startsWith("data: ")) return@transform
                val payload = line.removePrefix("data: ")
                try {
                    val obj = JSONObject(payload)
                    if (obj.has("error")) {
                        throw InferenceError.GenerationFailed(
                            IllegalStateException(obj.getString("error"))
                        )
                    }
                    if (obj.has("usage")) {
                        val usage = obj.getJSONObject("usage")
                        val promptTokens = usage.optInt("prompt_tokens", 0)
                        val completionTokens = usage.optInt("completion_tokens", 0)
                        if (promptTokens > 0 || completionTokens > 0) {
                            _lastUsage = GenerationUsage(promptTokens, completionTokens)
                        }
                        recalibrate(promptTokens)
                    }
                    val chunk = obj.optString("token", "")
                    if (chunk.isNotEmpty()) emit(chunk)
                } catch (ie: InferenceError) {
                    throw ie
                } catch (_: Exception) { /* skip malformed line */ }
            }
            .catch { cause ->
                throw when (cause) {
                    is InferenceError -> cause
                    is HttpError.Status -> InferenceError.GenerationFailed(
                        IllegalStateException("Server returned HTTP ${cause.code}${formatErrorBody(cause.body)}")
                    )
                    is HttpError.Timeout -> InferenceError.GenerationFailed(
                        IllegalStateException(
                            "Remote server stopped responding (no data for ${STREAM_IDLE_TIMEOUT_MS / 60_000} min)."
                        )
                    )
                    else -> InferenceError.GenerationFailed(cause)
                }
            }
    }

    override suspend fun transcribe(pcm16MonoBytes: ByteArray, promptOverride: String?): String {
        throw InferenceError.TranscriptionFailed(UnsupportedOperationException("Transcription is not supported on the remote backend"))
    }

    /**
     * Pure length-based estimate using the [calibratedCharsPerToken] ratio learned from the
     * server's reported prompt-token counts. Unlike before, this no longer returns the previous
     * completion's exact count for an unrelated call — exact counts are surfaced via
     * [consumeLastUsage] instead, so estimates and measurements never get crossed.
     */
    override suspend fun countTokens(text: String): Int =
        ContextBudget.estimateTokens(text, calibratedCharsPerToken)

    override fun consumeLastUsage(): GenerationUsage? {
        val usage = _lastUsage
        _lastUsage = null
        return usage
    }

    /**
     * Nudges [calibratedCharsPerToken] toward the ratio implied by the last prompt's character
     * length and the server's actual prompt-token count, smoothed so a single odd request can't
     * swing the estimate wildly. Clamped to a sane band for SentencePiece-style tokenizers.
     */
    private fun recalibrate(promptTokens: Int) {
        if (promptTokens <= 0 || lastPromptChars <= 0) return
        val observed = lastPromptChars.toFloat() / promptTokens
        val blended = calibratedCharsPerToken * (1 - CALIBRATION_ALPHA) + observed * CALIBRATION_ALPHA
        calibratedCharsPerToken = blended.coerceIn(2.0f, 8.0f)
    }

    private fun formatErrorBody(body: String): String =
        body.takeIf { it.isNotBlank() }?.let { " — ${it.take(200)}" }.orEmpty()
}
