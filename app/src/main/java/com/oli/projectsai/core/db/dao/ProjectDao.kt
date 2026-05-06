package com.oli.projectsai.core.db.dao

import androidx.room.*
import com.oli.projectsai.core.db.entity.Project
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects WHERE isSecret = 0 AND deletedAt IS NULL ORDER BY updatedAt DESC")
    fun getAll(): Flow<List<Project>>

    @Query("SELECT * FROM projects WHERE isSecret = 1 AND deletedAt IS NULL ORDER BY updatedAt DESC")
    fun getSecretAll(): Flow<List<Project>>

    @Query("SELECT * FROM projects WHERE id = :id AND deletedAt IS NULL")
    suspend fun getById(id: Long): Project?

    @Query("SELECT * FROM projects WHERE id = :id AND deletedAt IS NULL")
    fun getByIdFlow(id: Long): Flow<Project?>

    @Insert
    suspend fun insert(project: Project): Long

    @Update
    suspend fun update(project: Project)

    @Query("UPDATE projects SET deletedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE projects SET accumulatedMemory = :memory, updatedAt = :now WHERE id = :projectId")
    suspend fun updateMemory(projectId: Long, memory: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE projects SET manualContext = :context, updatedAt = :now WHERE id = :projectId")
    suspend fun updateManualContext(projectId: Long, context: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE projects SET pinnedMemories = :pinned, updatedAt = :now WHERE id = :projectId")
    suspend fun updatePinnedMemories(projectId: Long, pinned: List<String>, now: Long = System.currentTimeMillis())

    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    suspend fun getAllForSync(): List<Project>

    @Query("SELECT * FROM projects WHERE remoteId = :remoteId LIMIT 1")
    suspend fun getByRemoteId(remoteId: String): Project?

    @Query("UPDATE projects SET remoteId = :remoteId WHERE id = :id")
    suspend fun updateRemoteId(id: Long, remoteId: String)
}
