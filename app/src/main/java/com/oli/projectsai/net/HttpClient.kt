package com.oli.projectsai.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Errors thrown by [HttpClient]. Sealed so callers can pattern-match on the kind of failure
 * rather than parsing free-form messages.
 */
sealed class HttpError(message: String) : Exception(message) {
    /** Underlying socket / DNS / IO failure — the request never completed. */
    class Network(cause: Throwable) :
        HttpError(cause.message ?: cause::class.simpleName ?: "Network error")

    /** Server returned a non-2xx response. [body] is the response body verbatim (truncated by the OS). */
    class Status(val code: Int, val body: String) : HttpError("HTTP $code")

    /** Read or connect timeout. */
    class Timeout(detail: String) : HttpError(detail)
}

/**
 * Tiny `HttpURLConnection` wrapper centralising the auth header, JSON content-type, response-code
 * check, and error mapping that was otherwise hand-rolled per call site. Stays on
 * `HttpURLConnection` rather than pulling in OkHttp/Retrofit so the APK doesn't grow for what
 * boils down to four endpoints.
 *
 * The streaming variant returns a Flow so callers can use the standard `takeWhile`/`map`
 * operators to terminate on protocol-specific markers (e.g. SSE `[DONE]`).
 */
@Singleton
class HttpClient @Inject constructor() {

    /** Performs a GET and returns the response body as UTF-8 text. */
    suspend fun get(
        url: String,
        bearer: String? = null,
        connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
        headers: Map<String, String> = emptyMap()
    ): String = withContext(Dispatchers.IO) {
        execute("GET", url, body = null, bearer, connectTimeoutMs, readTimeoutMs, headers)
    }

    /** Performs a POST with a JSON body and returns the response body as UTF-8 text. */
    suspend fun postJson(
        url: String,
        body: String,
        bearer: String? = null,
        connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
        headers: Map<String, String> = emptyMap()
    ): String = withContext(Dispatchers.IO) {
        execute("POST", url, body, bearer, connectTimeoutMs, readTimeoutMs, headers)
    }

    /** Performs a PUT with a JSON body and returns the response body as UTF-8 text. */
    suspend fun putJson(
        url: String,
        body: String,
        bearer: String? = null,
        connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
        headers: Map<String, String> = emptyMap()
    ): String = withContext(Dispatchers.IO) {
        execute("PUT", url, body, bearer, connectTimeoutMs, readTimeoutMs, headers)
    }

    /**
     * Streams response lines as a Flow. Empty lines are skipped; non-empty lines are emitted as
     * they arrive. Use `takeWhile` to stop early on a sentinel (e.g. SSE `[DONE]`).
     *
     * Set [readTimeoutMs] = 0 for streams that may sit idle indefinitely (e.g. model pulls).
     *
     * Cancellation is forced by closing the underlying connection in the flow's `finally` block;
     * `BufferedReader.readLine()` is otherwise blocking and ignores `Job.cancel()`.
     */
    @OptIn(InternalCoroutinesApi::class)
    fun streamLines(
        url: String,
        method: String = "GET",
        body: String? = null,
        bearer: String? = null,
        connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
        headers: Map<String, String> = emptyMap()
    ): Flow<String> = flow {
        val conn = openConnection(url, method, bearer, connectTimeoutMs, readTimeoutMs, headers, hasBody = body != null)
        val cancelHook = currentCoroutineContext()[Job]?.invokeOnCompletion(onCancelling = true) {
            runCatching { conn.disconnect() }
        }
        try {
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            if (code !in 200..299) throw HttpError.Status(code, readErrorDetail(conn))
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isNotEmpty()) emit(line)
                }
            }
        } catch (e: HttpError) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw HttpError.Timeout(e.message ?: "Read timed out")
        } catch (e: IOException) {
            throw HttpError.Network(e)
        } finally {
            cancelHook?.dispose()
            runCatching { conn.disconnect() }
        }
    }.flowOn(Dispatchers.IO)

    private fun execute(
        method: String,
        url: String,
        body: String?,
        bearer: String?,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        headers: Map<String, String>
    ): String {
        val conn = openConnection(url, method, bearer, connectTimeoutMs, readTimeoutMs, headers, hasBody = body != null)
        try {
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            if (code !in 200..299) throw HttpError.Status(code, readErrorDetail(conn))
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: HttpError) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw HttpError.Timeout(e.message ?: "Read timed out")
        } catch (e: IOException) {
            throw HttpError.Network(e)
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun openConnection(
        url: String,
        method: String,
        bearer: String?,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        headers: Map<String, String>,
        hasBody: Boolean
    ): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = connectTimeoutMs
        readTimeout = readTimeoutMs
        if (!bearer.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $bearer")
        if (hasBody) setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        headers.forEach { (k, v) -> setRequestProperty(k, v) }
    }

    private fun readErrorDetail(conn: HttpURLConnection): String =
        runCatching {
            (conn.errorStream ?: conn.inputStream).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull().orEmpty()

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000
        const val DEFAULT_READ_TIMEOUT_MS = 30_000
    }
}
