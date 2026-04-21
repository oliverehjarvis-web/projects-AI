package com.oli.projectsai.inference

import com.oli.projectsai.data.preferences.RemoteSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
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
    private val remoteSettings: RemoteSettings
) : InferenceBackend {

    companion object {
        private const val CHARS_PER_TOKEN = 4.0f
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var _serverUrl: String = ""
    @Volatile private var _apiToken: String = ""
    @Volatile private var _model: String = "gemma3:4b-it-q4_K_M"
    @Volatile private var _isLoaded: Boolean = false

    init {
        remoteSettings.serverUrl.onEach { _serverUrl = it }.launchIn(scope)
        remoteSettings.apiToken.onEach { _apiToken = it }.launchIn(scope)
        remoteSettings.defaultModel.onEach { _model = it }.launchIn(scope)
    }

    override val id: String = "remote_http"
    override val displayName: String = "Remote Server"
    override val isAvailable: Boolean get() = _serverUrl.isNotBlank()
    override val isLoaded: Boolean get() = _isLoaded
    override val loadedModel: ModelInfo? = null

    override suspend fun loadModel(modelInfo: ModelInfo) {
        val url = _serverUrl.ifBlank { throw InferenceError.LoadFailed(IllegalStateException("Server URL not configured")) }
        withContext(Dispatchers.IO) {
            val conn = (URL("$url/v1/health").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Authorization", "Bearer $_apiToken")
            }
            try {
                val code = conn.responseCode
                if (code != 200) throw InferenceError.LoadFailed(IllegalStateException("Health check failed: HTTP $code"))
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

    override suspend fun generate(
        systemPrompt: String,
        messages: List<ChatMessage>,
        config: GenerationConfig
    ): Flow<String> {
        val url = _serverUrl.ifBlank { throw InferenceError.ModelNotLoaded }
        val token = _apiToken
        val model = _model

        val body = JSONObject().apply {
            put("system_prompt", systemPrompt)
            put("messages", JSONArray().apply {
                messages.forEach { m ->
                    put(JSONObject().apply {
                        put("role", m.role)
                        put("content", m.content)
                    })
                }
            })
            put("config", JSONObject().apply {
                put("model", model)
                put("max_tokens", config.maxOutputTokens)
                put("temperature", config.temperature.toDouble())
                put("top_p", config.topP.toDouble())
            })
        }.toString()

        return flow {
            val conn = (URL("$url/v1/generate").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 0  // infinite: stream stays open until [DONE]
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "text/event-stream")
                setRequestProperty("Authorization", "Bearer $token")
                outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            try {
                val code = conn.responseCode
                if (code != 200) throw InferenceError.GenerationFailed(
                    IllegalStateException("Server returned HTTP $code")
                )
                BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val l = line ?: continue
                        if (!l.startsWith("data: ")) continue
                        val payload = l.removePrefix("data: ")
                        if (payload == "[DONE]") return@flow
                        try {
                            val token = JSONObject(payload).getString("token")
                            if (token.isNotEmpty()) emit(token)
                        } catch (_: Exception) { /* skip malformed line */ }
                    }
                }
            } catch (ie: InferenceError) {
                throw ie
            } catch (t: Throwable) {
                throw InferenceError.GenerationFailed(t)
            } finally {
                conn.disconnect()
            }
        }.flowOn(Dispatchers.IO)
    }

    override suspend fun transcribe(pcm16MonoBytes: ByteArray): String {
        throw InferenceError.TranscriptionFailed(UnsupportedOperationException("Transcription is not supported on the remote backend"))
    }

    override suspend fun countTokens(text: String): Int =
        if (text.isEmpty()) 0
        else (text.length / CHARS_PER_TOKEN).toInt().coerceAtLeast(1)
}
