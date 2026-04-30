package com.oli.projectsai.data.appscript

import com.oli.projectsai.data.db.entity.AppScriptAuthMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppScriptClient @Inject constructor(
    private val oauthManager: GoogleOAuthManager
) {

    class AppScriptError(message: String, val statusCode: Int = -1) : Exception(message)

    /** Routes by auth mode and returns the raw response body the script produced. */
    suspend fun dispatch(tool: ResolvedAppScriptTool, args: JSONObject): String =
        when (tool.authMode) {
            AppScriptAuthMode.SHARED_SECRET -> callWebApp(tool.webAppUrl, tool.secret, args)
            AppScriptAuthMode.OAUTH -> callScriptsApi(tool.scriptId, tool.functionName, args)
        }

    /**
     * Calls a Web App deployment ("Anyone" access). Body shape:
     *   { "secret": "...", "args": { ... } }
     * The user's doPost reads e.postData.contents and JSON.parse's it.
     */
    suspend fun callWebApp(url: String, secret: String?, args: JSONObject): String =
        withContext(Dispatchers.IO) {
            require(url.isNotBlank()) { "Web App URL not configured." }
            val body = JSONObject().apply {
                if (!secret.isNullOrBlank()) put("secret", secret)
                put("args", args)
            }.toString()
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 10_000
                readTimeout = 30_000
                instanceFollowRedirects = true   // Apps Script 302s to *.googleusercontent.com
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json, text/plain, */*")
                setRequestProperty("User-Agent", "ProjectsAI-Android")
            }
            try {
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                readResponseOrThrow(conn)
            } finally {
                conn.disconnect()
            }
        }

    /**
     * Calls https://script.googleapis.com/v1/scripts/{scriptId}:run with the user's Google
     * OAuth access token. The script must live in the same GCP project as the OAuth client.
     */
    suspend fun callScriptsApi(scriptId: String, functionName: String, args: JSONObject): String =
        withContext(Dispatchers.IO) {
            require(scriptId.isNotBlank()) { "Apps Script ID not configured." }
            require(functionName.isNotBlank()) { "Apps Script function name not configured." }
            val token = oauthManager.accessToken()
                ?: throw AppScriptError("Not connected to Google. Reconnect from the data source settings.")
            val raw = postScriptsApi(scriptId, functionName, args, token)
            // Surface Apps Script's structured error payload if present.
            val parsed = runCatching { JSONObject(raw) }.getOrNull()
            val error = parsed?.optJSONObject("error")
            if (error != null) {
                val details = error.optJSONArray("details")
                val msg = (0 until (details?.length() ?: 0))
                    .firstNotNullOfOrNull { details?.optJSONObject(it)?.optString("errorMessage") }
                    ?: error.optString("message", "Apps Script returned an error.")
                throw AppScriptError(msg, error.optInt("code", -1))
            }
            // Unwrap response.result so the model gets the function's return value, not wrapper.
            val result = parsed?.optJSONObject("response")?.opt("result")
            when (result) {
                null -> raw
                is JSONObject, is JSONArray -> result.toString()
                else -> result.toString()
            }
        }

    private fun postScriptsApi(scriptId: String, functionName: String, args: JSONObject, token: String): String {
        val url = "https://script.googleapis.com/v1/scripts/$scriptId:run"
        val params = JSONArray().apply { put(args) }
        val body = JSONObject().apply {
            put("function", functionName)
            put("parameters", params)
            put("devMode", false)
        }.toString()
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "ProjectsAI-Android")
        }
        return try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            readResponseOrThrow(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun readResponseOrThrow(conn: HttpURLConnection): String {
        val code = conn.responseCode
        return if (code in 200..299) {
            conn.inputStream.bufferedReader().use { it.readText() }
        } else {
            val err = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
            val excerpt = err.take(500).ifBlank { conn.responseMessage ?: "" }
            throw AppScriptError("HTTP $code from ${conn.url.host}: $excerpt", statusCode = code)
        }
    }
}
