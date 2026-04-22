package com.oli.projectsai.data.db.dao

import androidx.room.*
import com.oli.projectsai.data.db.entity.Chat
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Query("SELECT * FROM chats WHERE projectId = :projectId AND deletedAt IS NULL ORDER BY updatedAt DESC")
    fun getByProject(projectId: Long): Flow<List<Chat>>

    @Query("SELECT * FROM chats WHERE id = :id AND deletedAt IS NULL")
    suspend fun getById(id: Long): Chat?

    @Insert
    suspend fun insert(chat: Chat): Long

    @Update
    suspend fun update(chat: Chat)

    // Soft-delete: stamp deletedAt so the next sync push tombstones the chat on
    // the server. A hard DELETE would drop the row locally before the push
    // iterates it, which is how the web ended up seeing ghost chats.
    @Query("UPDATE chats SET deletedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE chats SET deletedAt = :now, updatedAt = :now WHERE id IN (:ids)")
    suspend fun softDeleteByIds(ids: List<Long>, now: Long = System.currentTimeMillis())

    @Query("UPDATE chats SET deletedAt = :now, updatedAt = :now WHERE projectId = :projectId AND deletedAt IS NULL")
    suspend fun softDeleteByProject(projectId: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE chats SET title = :title, updatedAt = :now WHERE id = :chatId")
    suspend fun updateTitle(chatId: Long, title: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE chats SET webSearchEnabled = :enabled WHERE id = :chatId")
    suspend fun updateWebSearchEnabled(chatId: Long, enabled: Boolean)

    @Query("SELECT * FROM chats ORDER BY updatedAt DESC")
    suspend fun getAllForSync(): List<Chat>

    @Query("SELECT * FROM chats WHERE remoteId = :remoteId LIMIT 1")
    suspend fun getByRemoteId(remoteId: String): Chat?

    @Query("UPDATE chats SET remoteId = :remoteId WHERE id = :id")
    suspend fun updateRemoteId(id: Long, remoteId: String)
}
