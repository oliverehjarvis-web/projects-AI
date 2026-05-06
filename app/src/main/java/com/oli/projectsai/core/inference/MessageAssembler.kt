package com.oli.projectsai.core.inference

import com.oli.projectsai.core.attachments.AttachmentStore
import com.oli.projectsai.core.db.entity.MessageRole
import com.oli.projectsai.core.repository.ChatRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pulls the persisted messages for a chat and turns them into wire-format [ChatMessage]s.
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
) {
    suspend fun assemble(
        chatId: Long,
        currentUserContent: String?,
        currentAttachments: List<String>,
    ): List<ChatMessage> {
        val msgs = chatRepository.getMessagesFlow(chatId).first()
        val dbMessages = msgs.mapIndexed { idx, msg ->
            val isLast = idx == msgs.lastIndex
            val bytes = if (isLast && msg.role == MessageRole.USER) {
                msg.attachmentPaths.map { attachmentStore.readBytes(it) }
            } else emptyList()
            ChatMessage(
                role = msg.role.toWireRole(),
                content = msg.content,
                imageBytes = bytes,
            )
        }
        // If the caller passed a `currentUserContent` that the DB didn't already capture as
        // the latest turn, append it. Guards against double-emit when the DB write race-wins
        // and the message is already there.
        return if (
            currentUserContent != null &&
            dbMessages.lastOrNull()?.content != currentUserContent
        ) {
            val bytes = currentAttachments.map { attachmentStore.readBytes(it) }
            dbMessages + ChatMessage(
                role = "user",
                content = currentUserContent,
                imageBytes = bytes,
            )
        } else {
            dbMessages
        }
    }
}
