package com.oli.projectsai.data.github

import com.oli.projectsai.data.preferences.GitHubSettings
import com.oli.projectsai.net.HttpClient
import com.oli.projectsai.net.HttpError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Minimal GitHub REST v3 wrapper. Three operations are enough for v1:
 *  - [listRepos]  — what repos can the user pick from
 *  - [tree]       — full directory listing for a repo at a ref
 *  - [file]       — raw bytes of a single file at a ref
 */
@Singleton
class GitHubClient @Inject constructor(
    private val settings: GitHubSettings,
    private val httpClient: HttpClient
) {

    data class Repo(
        val owner: String,
        val name: String,
        val defaultBranch: String,
        /** Repo size in KB as reported by GitHub. Mostly informative. */
        val sizeKb: Int,
        val updatedAt: String
    )

    data class TreeEntry(
        val path: String,
        /** "blob" for files, "tree" for directories. */
        val type: String,
        val size: Int,
        val sha: String
    )

    data class RepoTree(
        val owner: String,
        val name: String,
        val ref: String,
        val sha: String,
        /** True if GitHub elided entries because the tree exceeds the API limit. */
        val truncated: Boolean,
        val entries: List<TreeEntry>
    )

    data class FileBlob(
        val path: String,
        val sha: String,
        val sizeBytes: Int,
        val text: String
    )

    class GitHubError(message: String, val statusCode: Int = -1) : Exception(message)

    suspend fun listRepos(): List<Repo> = withContext(Dispatchers.IO) {
        val arr = getJsonArray("/user/repos?per_page=100&sort=updated&affiliation=owner,collaborator,organization_member")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val ownerObj = o.getJSONObject("owner")
            Repo(
                owner = ownerObj.getString("login"),
                name = o.getString("name"),
                defaultBranch = o.optString("default_branch", "main"),
                sizeKb = o.optInt("size", 0),
                updatedAt = o.optString("updated_at", "")
            )
        }
    }

    suspend fun tree(owner: String, repo: String, ref: String): RepoTree = withContext(Dispatchers.IO) {
        val obj = getJsonObject("/repos/$owner/$repo/git/trees/${URLEncoder.encode(ref, "UTF-8")}?recursive=1")
        val sha = obj.optString("sha", ref)
        val truncated = obj.optBoolean("truncated", false)
        val arr = obj.getJSONArray("tree")
        val entries = (0 until arr.length()).map { i ->
            val e = arr.getJSONObject(i)
            TreeEntry(
                path = e.getString("path"),
                type = e.getString("type"),
                size = e.optInt("size", 0),
                sha = e.optString("sha", "")
            )
        }
        RepoTree(owner, repo, ref, sha, truncated, entries)
    }

    suspend fun file(owner: String, repo: String, path: String, ref: String): FileBlob = withContext(Dispatchers.IO) {
        val encodedPath = path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        val encodedRef = URLEncoder.encode(ref, "UTF-8")
        val obj = getJsonObject("/repos/$owner/$repo/contents/$encodedPath?ref=$encodedRef")
        val encoding = obj.optString("encoding", "base64")
        val raw = obj.optString("content", "")
        val text = when (encoding) {
            "base64" -> {
                val cleaned = raw.replace("\n", "").replace("\r", "")
                String(android.util.Base64.decode(cleaned, android.util.Base64.DEFAULT), Charsets.UTF_8)
            }
            "utf-8" -> raw
            else -> raw
        }
        FileBlob(
            path = obj.getString("path"),
            sha = obj.getString("sha"),
            sizeBytes = obj.optInt("size", text.toByteArray(Charsets.UTF_8).size),
            text = text
        )
    }

    suspend fun whoami(): String = withContext(Dispatchers.IO) {
        getJsonObject("/user").optString("login", "(unknown)")
    }

    private suspend fun getJsonObject(path: String): JSONObject = JSONObject(get(path))

    private suspend fun getJsonArray(path: String) = org.json.JSONArray(get(path))

    private suspend fun get(path: String): String {
        val pat = settings.pat.first()
        if (pat.isBlank()) throw GitHubError("No GitHub PAT configured. Set one in Settings → GitHub.")
        return try {
            httpClient.get(
                url = "$BASE_URL$path",
                bearer = pat,
                connectTimeoutMs = 10_000,
                readTimeoutMs = 30_000,
                headers = mapOf(
                    "Accept" to "application/vnd.github+json",
                    "X-GitHub-Api-Version" to "2022-11-28",
                    "User-Agent" to "ProjectsAI-Android"
                )
            )
        } catch (e: HttpError.Status) {
            val message = runCatching { JSONObject(e.body).optString("message") }.getOrNull().orEmpty()
            throw GitHubError(
                "GitHub ${e.code} on $path${if (message.isNotBlank()) ": $message" else ""}",
                statusCode = e.code
            )
        }
    }

    private companion object {
        const val BASE_URL = "https://api.github.com"
    }
}
