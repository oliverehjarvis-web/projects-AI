package com.oli.projectsai.data.repository

import com.oli.projectsai.data.db.dao.ChatDao
import com.oli.projectsai.data.db.dao.MessageDao
import com.oli.projectsai.data.db.entity.Chat
import com.oli.projectsai.data.db.entity.Message
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatRepository @Inject constructor(
    private val chatDao: ChatDao,
    private val messageDao: MessageDao
) {
    fun getChatsByProject(projectId: Long): Flow<List<Chat>> = chatDao.getByProject(projectId)

    suspend fun getChat(id: Long): Chat? = chatDao.getById(id)

    suspend fun createChat(chat: Chat): Long = chatDao.insert(chat)

    suspend fun updateChat(chat: Chat) = chatDao.update(chat)

    suspend fun updateChatTitle(chatId: Long, title: String) = chatDao.updateTitle(chatId, title)

    suspend fun updateWebSearchEnabled(chatId: Long, enabled: Boolean) =
        chatDao.updateWebSearchEnabled(chatId, enabled)

    suspend fun deleteChat(chat: Chat) = chatDao.delete(chat)

    suspend fun deleteChats(ids: List<Long>) = chatDao.deleteByIds(ids)

    fun getMessagesFlow(chatId: Long): Flow<List<Message>> = messageDao.getByChatFlow(chatId)

    suspend fun getMessages(chatId: Long): List<Message> = messageDao.getByChat(chatId)

    suspend fun addMessage(message: Message): Long = messageDao.insert(message)

    suspend fun updateMessage(message: Message) = messageDao.update(message)
}
