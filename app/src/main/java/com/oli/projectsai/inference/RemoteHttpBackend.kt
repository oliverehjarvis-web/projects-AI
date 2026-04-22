package com.oli.projectsai.inference

import com.oli.projectsai.data.preferences.RemoteSettings
import com.oli.projectsai.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RemoteHttpBackend @Inject constructor(
    private val remoteSettings: RemoteSettings,
    @ApplicationScope private val scope: CoroutineScope
) : InferenceBackend {

    companion object {
        private const val CHARS_PER_TOKEN = 4.0f
        // SSE idle timeout: generous to accommodate cold starts on large models, long prompt
        // processing, and slower hardware. Ten minutes of silence before we assume the stream has
        // hung — first-token latency is the usual offender, tokens then flow every few hundred ms.
        private const val STREAM_IDLE_TIMEOUT_MS = 600_000
    }

    // Cached for the synchronous `isAvailable` property; the suspend paths read freshly.
    @Volatile private var serverUrlSnapshot: String = ""
    @Volatile private var _isLoaded: Boolean = false
    // Set when the server reports token usage in the DONE event; consumed by countTokens().
    @Volatile private var _lastCompletionTokens: Int? = null

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
        withContext(Dispatchers.IO) {
            val conn = (URL("$url/v1/health").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Authorization", "Bearer $token")
            }
            try {
                val code = conn.responseCode
                if (code != 200) {
                    throw InferenceError.LoadFailed(
                        IllegalStateException("Health check failed: HTTP $code${readErrorDetail(conn)}")
                    )
                }
            } catch (ie: InferenceError) {
                throw ie
            } catch (t: Throwable) {
                throw InferenceError.LoadFailed(t)
            } finally {
                conn.disconnect()
            }
        }
        _isLoaded = true
    }

    override suspend fun unloadModel() {
        _isLoaded = false
    }

    @OptIn(InternalCoroutinesApi::class)
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
            })
        }.toString()

        return flow {
            val conn = (URL("$url/v1/generate").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                // Idle timeout per read — a stalled stream raises SocketTimeoutException instead of
                // hanging the client forever.
                readTimeout = STREAM_IDLE_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "text/event-stream")
                setRequestProperty("Authorization", "Bearer $token")
                outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            // BufferedReader.readLine() is a blocking syscall that job.cancel() cannot interrupt.
            // Force-close the socket on cancellation so the read raises and the flow unwinds.
            val cancelHook = currentCoroutineContext()[Job]?.invokeOnCompletion(onCancelling = true) {
                runCatching { conn.disconnect() }
            }
            try {
                val code = conn.responseCode
                if (code != 200) {
                    throw InferenceError.GenerationFailed(
                        IllegalStateException("Server returned HTTP $code${readErrorDetail(conn)}")
                    )
                }
                BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val l = line ?: continue
                        if (!l.startsWith("data: ")) continue
                        val payload = l.removePrefix("data: ")
                        if (payload == "[DONE]") return@flow
                        try {
                            val obj = JSONObject(payload)
                            if (obj.has("error")) {
                                throw InferenceError.GenerationFailed(
                                    IllegalStateException(obj.getString("error"))
                                )
                            }
                            if (obj.has("usage")) {
                                val usage = obj.getJSONObject("usage")
                                val ct = usage.optInt("completion_tokens", 0)
                                if (ct > 0) _lastCompletionTokens = ct
                            }
                            val chunk = obj.optString("token", "")
                            if (chunk.isNotEmpty()) emit(chunk)
                        } catch (ie: InferenceError) {
                            throw ie
                        } catch (_: Exception) { /* skip malformed line */ }
                    }
                }
            } catch (ie: InferenceError) {
                throw ie
            } catch (t: java.net.SocketTimeoutException) {
                val mins = STREAM_IDLE_TIMEOUT_MS / 60_000
                throw InferenceError.GenerationFailed(
                    IllegalStateException("Remote server stopped responding (no data for ${mins} min).")
                )
            } catch (t: Throwable) {
                throw InferenceError.GenerationFailed(t)
            } finally {
                cancelHook?.dispose()
                conn.disconnect()
            }
        }.flowOn(Dispatchers.IO)
    }

    override suspend fun transcribe(pcm16MonoBytes: ByteArray): String {
        throw InferenceError.TranscriptionFailed(UnsupportedOperationException("Transcription is not supported on the remote backend"))
    }

    override suspend fun countTokens(text: String): Int {
        _lastCompletionTokens?.let { stored ->
            _lastCompletionTokens = null
            return stored
        }
        return if (text.isEmpty()) 0
        else (text.length / CHARS_PER_TOKEN).toInt().coerceAtLeast(1)
    }

    private fun readErrorDetail(conn: HttpURLConnection): String =
        runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { " — ${it.take(200)}" }
            .orEmpty()
}
