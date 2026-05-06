package com.oli.projectsai.core.db.dao

import androidx.room.*
import com.oli.projectsai.core.db.entity.QuickAction
import kotlinx.coroutines.flow.Flow

@Dao
interface QuickActionDao {
    @Query("SELECT * FROM quick_actions WHERE projectId = :projectId AND deletedAt IS NULL ORDER BY sortOrder ASC")
    fun getByProject(projectId: Long): Flow<List<QuickAction>>

    @Query("SELECT * FROM quick_actions WHERE id = :id AND deletedAt IS NULL")
    suspend fun getById(id: Long): QuickAction?

    @Insert
    suspend fun insert(action: QuickAction): Long

    @Update
    suspend fun update(action: QuickAction)

    @Query("UPDATE quick_actions SET deletedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE quick_actions SET deletedAt = :now, updatedAt = :now WHERE projectId = :projectId AND deletedAt IS NULL")
    suspend fun softDeleteByProject(projectId: Long, now: Long = System.currentTimeMillis())

    @Query("SELECT * FROM quick_actions ORDER BY sortOrder ASC")
    suspend fun getAllForSync(): List<QuickAction>

    @Query("SELECT * FROM quick_actions WHERE remoteId = :remoteId LIMIT 1")
    suspend fun getByRemoteId(remoteId: String): QuickAction?

    @Query("UPDATE quick_actions SET remoteId = :remoteId WHERE id = :id")
    suspend fun updateRemoteId(id: Long, remoteId: String)
}
