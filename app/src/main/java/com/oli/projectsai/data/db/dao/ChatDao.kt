package com.oli.projectsai.data.db.dao

import androidx.room.*
import com.oli.projectsai.data.db.entity.Chat
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Query("SELECT * FROM chats WHERE projectId = :projectId ORDER BY updatedAt DESC")
    fun getByProject(projectId: Long): Flow<List<Chat>>

    @Query("SELECT * FROM chats WHERE id = :id")
    suspend fun getById(id: Long): Chat?

    @Insert
    suspend fun insert(chat: Chat): Long

    @Update
    suspend fun update(chat: Chat)

    @Delete
    suspend fun delete(chat: Chat)

    @Query("DELETE FROM chats WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("UPDATE chats SET title = :title, updatedAt = :now WHERE id = :chatId")
    suspend fun updateTitle(chatId: Long, title: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE chats SET webSearchEnabled = :enabled WHERE id = :chatId")
    suspend fun updateWebSearchEnabled(chatId: Long, enabled: Boolean)
}
