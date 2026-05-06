package com.oli.projectsai.core.db.dao

import androidx.room.*
import com.oli.projectsai.core.db.entity.Message
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
}
