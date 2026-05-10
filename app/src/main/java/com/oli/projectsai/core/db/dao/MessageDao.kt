package com.oli.projectsai.core.db.dao

import androidx.room.*
import com.oli.projectsai.core.db.entity.Message
import com.oli.projectsai.core.db.relation.MessageSearchHit
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE chatId = :chatId AND deletedAt IS NULL ORDER BY createdAt ASC")
    fun getByChatFlow(chatId: Long): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE chatId = :chatId AND deletedAt IS NULL ORDER BY createdAt ASC")
    suspend fun getByChat(chatId: Long): List<Message>

    @Insert
    suspend fun insert(message: Message): Long

    @Update
    suspend fun update(message: Message)

    @Query("UPDATE messages SET deletedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE messages SET deletedAt = :now, updatedAt = :now WHERE chatId = :chatId AND deletedAt IS NULL")
    suspend fun softDeleteByChat(chatId: Long, now: Long = System.currentTimeMillis())

    @Query("SELECT * FROM messages ORDER BY createdAt ASC")
    suspend fun getAllForSync(): List<Message>

    @Query("SELECT * FROM messages WHERE remoteId = :remoteId LIMIT 1")
    suspend fun getByRemoteId(remoteId: String): Message?

    @Query("UPDATE messages SET remoteId = :remoteId WHERE id = :id")
    suspend fun updateRemoteId(id: Long, remoteId: String)

    /**
     * Substring search across every message in every (non-secret, non-deleted) chat. `LIKE` is
     * fast enough for the typical single-user message volume and avoids an FTS migration. The
     * 50-row cap keeps the home-screen results list lightweight even on a noisy database.
     */
    @Query(
        """
        SELECT m.id AS messageId,
               m.chatId AS chatId,
               m.role AS role,
               m.content AS content,
               m.createdAt AS createdAt,
               c.title AS chatTitle,
               p.id AS projectId,
               p.name AS projectName
        FROM messages m
        INNER JOIN chats c ON m.chatId = c.id
        INNER JOIN projects p ON c.projectId = p.id
        WHERE m.deletedAt IS NULL
          AND c.deletedAt IS NULL
          AND p.deletedAt IS NULL
          AND p.isSecret = 0
          AND m.content LIKE '%' || :query || '%'
        ORDER BY m.createdAt DESC
        LIMIT 50
        """
    )
    suspend fun searchMessages(query: String): List<MessageSearchHit>
}
