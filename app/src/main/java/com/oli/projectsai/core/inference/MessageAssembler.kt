package com.oli.projectsai.core.inference

import com.oli.projectsai.core.attachments.AttachmentStore
import com.oli.projectsai.core.db.entity.MessageRole
import com.oli.projectsai.core.repository.ChatRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pulls the persisted messages for a chat, turns them into wire-format [ChatMessage]s, and
 * trims the history to the turns that actually fit in the model's context window via
 * [ContextBudget]. This trimmed list is what gets sent to *both* backends, so the sliding
 * window is identical across local and remote and the system prompt + memory are never the
 * casualty of a backend's own silent truncation.
 *
 * Image bytes are attached only to the LAST user message — earlier turns may have lost their
 * attachments to garbage collection (the AttachmentStore can prune stale paths) and the
 * model only ever conditions on the most recent vision payload anyway, so loading older
 * blobs is wasted memory + bandwidth.
 *
 * If the caller has staged a fresh user turn that hasn't been persisted yet
 * ([currentUserContent]), it's appended at the end with its attachments resolved.
 */
@Singleton
class MessageAssembler @Inject constructor(
    private val chatRepository: ChatRepository,
    private val attachmentStore: AttachmentStore,
    private val inferenceManager: InferenceManager,
) {
    data class Assembled(
        /** Chronological wire messages that fit the window — what we actually send. */
        val messages: List<ChatMessage>,
        /** Older turns left out of the window (still in the DB / transcript). */
        val droppedCount: Int,
        /** Summed token count of [messages]. */
        val conversationTokens: Int,
        /** Tokens left for the reply after system prompt + kept messages. */
        val outputBudget: Int,
    )

    private class Counted(val message: ChatMessage, val tokens: Int)

    suspend fun assemble(
        chatId: Long,
        currentUserContent: String?,
        currentAttachments: List<String>,
        contextLimit: Int,
        systemPromptTokens: Int,
        backendId: String? = null,
    ): Assembled {
        val msgs = chatRepository.getMessagesFlow(chatId).first()
        val dbCounted = msgs.mapIndexed { idx, msg ->
            val isLast = idx == msgs.lastIndex
            val bytes = if (isLast && msg.role == MessageRole.USER) {
                msg.attachmentPaths.map { attachmentStore.readBytes(it) }
            } else emptyList()
            Counted(
                message = ChatMessage(
                    role = msg.role.toWireRole(),
                    content = msg.content,
                    imageBytes = bytes,
                ),
                // Prefer the count persisted at insert time; fall back to a length estimate for
                // legacy rows written before token counts were stored.
                tokens = msg.tokenCount.takeIf { it > 0 } ?: ContextBudget.estimateTokens(msg.content),
            )
        }

        // If the caller passed a `currentUserContent` that the DB didn't already capture as the
        // latest turn, append it. Guards against double-emit when the DB write race-wins and the
        // message is already there.
        val all = if (
            currentUserContent != null &&
            dbCounted.lastOrNull()?.message?.content != currentUserContent
        ) {
            val bytes = currentAttachments.map { attachmentStore.readBytes(it) }
            dbCounted + Counted(
                message = ChatMessage(role = "user", content = currentUserContent, imageBytes = bytes),
                tokens = inferenceManager.countTokens(currentUserContent, backendId),
            )
        } else {
            dbCounted
        }

        val fit = ContextBudget.fit(
            contextLimit = contextLimit,
            systemPromptTokens = systemPromptTokens,
            messageTokens = all.map { it.tokens },
        )
        return Assembled(
            messages = all.drop(fit.keepFrom).map { it.message },
            droppedCount = fit.droppedCount,
            conversationTokens = fit.conversationTokens,
            outputBudget = fit.outputBudget,
        )
    }
}
