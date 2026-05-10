package com.oli.projectsai.core.db.relation

import com.oli.projectsai.core.db.entity.MessageRole

/**
 * Flat result row for a cross-project message search. Each hit carries enough context for the
 * UI to render the result line (project + chat + snippet) and navigate directly to the matched
 * message without an extra lookup.
 */
data class MessageSearchHit(
    val messageId: Long,
    val chatId: Long,
    val role: MessageRole,
    val content: String,
    val createdAt: Long,
    val chatTitle: String,
    val projectId: Long,
    val projectName: String,
)
