package com.oli.projectsai.features.settings.update

import android.content.Context
import android.util.Log
import com.oli.projectsai.core.net.HttpClient
import com.oli.projectsai.core.net.HttpError
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UpdateChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: HttpClient
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
    suspend fun checkForUpdate(currentVersion: String): UpdateInfo? {
        val body = try {
            httpClient.get(
                url = API_URL,
                connectTimeoutMs = TIMEOUT_MS,
                readTimeoutMs = TIMEOUT_MS,
                headers = mapOf("Accept" to "application/vnd.github+json")
            )
        } catch (e: HttpError.Status) {
            throw Exception("GitHub API returned HTTP ${e.code}")
        }
        val json = JSONObject(body)
        val tagName = json.getString("tag_name")
        val remoteVersion = tagName.trimStart('v')

        if (!isNewer(remoteVersion, currentVersion)) {
            Log.d(TAG, "Already up to date ($currentVersion)")
            return null
        }

        val assets = json.getJSONArray("assets")
        val downloadUrl = (0 until assets.length())
            .asSequence()
            .map { assets.getJSONObject(it) }
            .firstOrNull { it.getString("name").endsWith(".apk") }
            ?.getString("browser_download_url")
            ?: throw Exception("No APK asset found in release $tagName")

        Log.i(TAG, "Update available: $remoteVersion (current: $currentVersion)")
        return UpdateInfo(tagName = tagName, downloadUrl = downloadUrl, version = remoteVersion)
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
