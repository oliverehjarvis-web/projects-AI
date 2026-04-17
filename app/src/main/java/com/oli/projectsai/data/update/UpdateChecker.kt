package com.oli.projectsai.data.update

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UpdateChecker @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "UpdateChecker"
        private const val API_URL =
            "https://api.github.com/repos/oliverehjarvis-web/projects-AI/releases/latest"
        private const val TIMEOUT_MS = 10_000
    }

    /**
     * Fetches the latest GitHub Release and returns [UpdateInfo] if the remote version is
     * newer than [currentVersion], or `null` if already up to date.
     *
     * @param currentVersion the app's current version string (e.g. "1.1.1")
     * @throws Exception on network failure or unexpected response
     */
    suspend fun checkForUpdate(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        val conn = (URL(API_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
        }
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw Exception("GitHub API returned HTTP $code")
            }
            val body = conn.inputStream.use { it.bufferedReader().readText() }
            val json = JSONObject(body)
            val tagName = json.getString("tag_name")
            val remoteVersion = tagName.trimStart('v')

            if (!isNewer(remoteVersion, currentVersion)) {
                Log.d(TAG, "Already up to date ($currentVersion)")
                return@withContext null
            }

            val assets = json.getJSONArray("assets")
            val downloadUrl = (0 until assets.length())
                .asSequence()
                .map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name").endsWith(".apk") }
                ?.getString("browser_download_url")
                ?: throw Exception("No APK asset found in release $tagName")

            Log.i(TAG, "Update available: $remoteVersion (current: $currentVersion)")
            UpdateInfo(tagName = tagName, downloadUrl = downloadUrl, version = remoteVersion)
        } finally {
            conn.disconnect()
        }
    }

    /** Returns true if [remote] is a higher semantic version than [current]. */
    private fun isNewer(remote: String, current: String): Boolean {
        val r = remote.split(".").mapNotNull { it.toIntOrNull() }
        val c = current.split(".").mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(r.size, c.size)) {
            val rv = r.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (rv != cv) return rv > cv
        }
        return false
    }
}
