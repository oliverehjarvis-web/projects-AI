package com.oli.projectsai.data.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches a URL and extracts the main article text. Uses a coarse regex strip rather than a full
 * HTML parser — good enough for news/blog pages, cheap, and no extra dependency.
 */
@Singleton
class PageFetcher @Inject constructor() {

    suspend fun fetch(url: String, maxChars: Int = 2000): String = withContext(Dispatchers.IO) {
        runCatching { fetchInternal(url, maxChars) }.getOrDefault("")
    }

    private fun fetchInternal(initialUrl: String, maxChars: Int): String {
        var current = initialUrl
        repeat(MAX_REDIRECTS) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 12_000
                requestMethod = "GET"
                // Manual redirect handling: HttpURLConnection won't follow http<->https.
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", BROWSER_UA)
                setRequestProperty(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                )
                setRequestProperty("Accept-Language", "en-GB,en;q=0.9")
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location") ?: return ""
                    current = URL(URL(current), location).toString()
                    return@repeat
                }
                if (code !in 200..299) return ""
                val contentType = conn.contentType.orEmpty().lowercase()
                if (contentType.isNotEmpty() &&
                    !contentType.contains("text/") &&
                    !contentType.contains("xhtml") &&
                    !contentType.contains("xml") &&
                    !contentType.contains("json")
                ) return ""
                val charset = parseCharset(contentType)
                val html = conn.inputStream.use { stream ->
                    stream.reader(charset).buffered().readText()
                }
                return extractText(html).take(maxChars)
            } finally {
                conn.disconnect()
            }
        }
        return ""
    }

    private fun parseCharset(contentType: String): Charset {
        val match = CHARSET_REGEX.find(contentType) ?: return StandardCharsets.UTF_8
        val name = match.groupValues[1].trim().trim('"', '\'')
        return runCatching { Charset.forName(name) }.getOrDefault(StandardCharsets.UTF_8)
    }

    private fun extractText(html: String): String {
        val dropped = html
            .replace(BLOCK_TAGS_REGEX, " ")
            .replace(COMMENT_REGEX, " ")
            .replace(TAG_REGEX, " ")
        return dropped
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace(WHITESPACE_REGEX, " ")
            .trim()
    }

    private companion object {
        const val MAX_REDIRECTS = 5
        const val BROWSER_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        val BLOCK_TAGS_REGEX = Regex(
            "<(script|style|nav|header|footer|aside|form|noscript|svg)\\b[^>]*>.*?</\\1>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val CHARSET_REGEX = Regex("charset=([^;\\s]+)", RegexOption.IGNORE_CASE)
        val COMMENT_REGEX = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
        val TAG_REGEX = Regex("<[^>]+>")
        val WHITESPACE_REGEX = Regex("\\s+")
    }
}
