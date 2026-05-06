package com.oli.projectsai.features.linkedin.data

import com.oli.projectsai.core.db.dao.LinkedInSuggestionDao
import com.oli.projectsai.core.db.entity.LinkedInSuggestion
import com.oli.projectsai.core.db.entity.SuggestedAction
import com.oli.projectsai.core.db.entity.SuggestionStatus
import com.oli.projectsai.core.inference.ChatMessage
import com.oli.projectsai.core.inference.GenerationConfig
import com.oli.projectsai.core.inference.InferenceManager
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

private const val PRUNE_AFTER_DAYS = 7L

private const val RANKER_SYSTEM = """You triage LinkedIn feed posts for the user and propose how they should engage.

For each post, decide:
- score (0.0-1.0): how relevant the post is to a senior software engineer's professional interests.
- action: "like" if you'd give it a like, "comment" if a brief comment would add value, "skip" if neither.
- comment: if action is "comment", a 1-sentence reply (<= 140 chars) in a casual, professional voice. Otherwise empty string.

Output ONLY a single-line JSON object with exactly these keys, no prose, no markdown fences:
{"score": 0.0, "action": "skip", "comment": ""}"""

@Singleton
class LinkedInRepository @Inject constructor(
    private val client: LinkedInClient,
    private val dao: LinkedInSuggestionDao,
    private val inferenceManager: InferenceManager
) {
    val pending: Flow<List<LinkedInSuggestion>> = dao.pending()

    /** Returns the number of *new* suggestions inserted (existing URNs are kept as-is). */
    suspend fun refresh(maxPosts: Int = 10): Int {
        val posts = client.fetchFeed(maxPosts)
        var added = 0
        for (post in posts) {
            if (dao.getByUrn(post.urn) != null) continue
            val ranked = runCatching { rank(post) }.getOrElse { t ->
                Ranked(0.0f, SuggestedAction.SKIP, "", error = t.message ?: "ranking failed")
            }
            val newId = dao.insertIfNew(
                LinkedInSuggestion(
                    urn = post.urn,
                    authorName = post.authorName,
                    authorHeadline = post.authorHeadline,
                    postText = post.text,
                    postUrl = post.postUrl,
                    suggestedAction = ranked.action,
                    suggestedComment = ranked.comment.takeIf { it.isNotBlank() },
                    score = ranked.score,
                    errorMessage = ranked.error
                )
            )
            if (newId != -1L) added++
        }
        val cutoff = System.currentTimeMillis() - PRUNE_AFTER_DAYS * 24 * 3600 * 1000L
        dao.pruneResolvedOlderThan(cutoff)
        return added
    }

    /** Sends the suggested (or user-edited) action to LinkedIn and marks the row APPROVED. */
    suspend fun approve(id: Long, editedComment: String? = null) {
        val s = dao.getById(id) ?: return
        val comment = editedComment?.trim()?.takeIf { it.isNotEmpty() } ?: s.suggestedComment
        if (editedComment != null && comment != null) dao.updateComment(id, comment)
        try {
            when (s.suggestedAction) {
                SuggestedAction.LIKE -> client.like(s.urn)
                SuggestedAction.COMMENT -> {
                    val text = comment ?: error("comment is empty")
                    client.comment(s.urn, text)
                }
                SuggestedAction.SKIP -> Unit
            }
            dao.setStatus(id, SuggestionStatus.APPROVED)
        } catch (t: Throwable) {
            dao.setStatus(id, SuggestionStatus.FAILED, errorMessage = t.message)
            throw t
        }
    }

    suspend fun reject(id: Long) {
        dao.setStatus(id, SuggestionStatus.REJECTED)
    }

    suspend fun sessionHealth(): Boolean = client.sessionHealth()

    // ── ranking ────────────────────────────────────────────────────────────

    private data class Ranked(
        val score: Float,
        val action: SuggestedAction,
        val comment: String,
        val error: String? = null
    )

    private suspend fun rank(post: RawFeedPost): Ranked {
        val userTurn = buildString {
            append("Post by ").append(post.authorName)
            if (post.authorHeadline.isNotBlank()) append(" (").append(post.authorHeadline).append(")")
            append(":\n\"\"\"\n")
            append(post.text.take(2000))
            append("\n\"\"\"")
        }
        val output = StringBuilder()
        // Cap output: a JSON line is short. Don't burn budget on chatty models.
        val config = GenerationConfig(maxOutputTokens = 200, applyDefaultPreamble = false)
        inferenceManager.generate(
            systemPrompt = RANKER_SYSTEM,
            messages = listOf(ChatMessage(role = "user", content = userTurn)),
            config = config
        ).collect { chunk -> output.append(chunk) }
        return parseRanked(output.toString())
    }

    private fun parseRanked(raw: String): Ranked {
        val json = extractFirstJsonObject(raw)
            ?: return Ranked(0f, SuggestedAction.SKIP, "", error = "model did not return JSON")
        val obj = runCatching { JSONObject(json) }.getOrNull()
            ?: return Ranked(0f, SuggestedAction.SKIP, "", error = "invalid JSON: ${json.take(200)}")
        val score = obj.optDouble("score", 0.0).toFloat().coerceIn(0f, 1f)
        val action = when (obj.optString("action").lowercase()) {
            "like" -> SuggestedAction.LIKE
            "comment" -> SuggestedAction.COMMENT
            else -> SuggestedAction.SKIP
        }
        val comment = obj.optString("comment").trim()
        return Ranked(score, action, comment)
    }

    /**
     * Pulls the first balanced {...} substring out of the model output, in case the model
     * wrapped JSON in prose or markdown fences despite the instruction.
     */
    private fun extractFirstJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        for (i in start until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }
}
