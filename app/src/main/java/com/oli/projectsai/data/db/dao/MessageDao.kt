package com.oli.projectsai.data.db.dao

import androidx.room.*
import com.oli.projectsai.data.db.entity.Message
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY createdAt ASC")
    fun getByChatFlow(chatId: Long): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY createdAt ASC")
    suspend fun getByChat(chatId: Long): List<Message>

    @Insert
    suspend fun insert(message: Message): Long

    @Update
    suspend fun update(message: Message)

    @Delete
    suspend fun delete(message: Message)

    @Query("DELETE FROM messages WHERE chatId = :chatId")
    suspend fun deleteByChat(chatId: Long)
}
