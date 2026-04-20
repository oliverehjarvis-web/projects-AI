package com.oli.projectsai.data.db.dao

import androidx.room.*
import com.oli.projectsai.data.db.entity.Project
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects WHERE isSecret = 0 ORDER BY updatedAt DESC")
    fun getAll(): Flow<List<Project>>

    @Query("SELECT * FROM projects WHERE isSecret = 1 ORDER BY updatedAt DESC")
    fun getSecretAll(): Flow<List<Project>>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun getById(id: Long): Project?

    @Query("SELECT * FROM projects WHERE id = :id")
    fun getByIdFlow(id: Long): Flow<Project?>

    @Insert
    suspend fun insert(project: Project): Long

    @Update
    suspend fun update(project: Project)

    @Delete
    suspend fun delete(project: Project)

    @Query("UPDATE projects SET accumulatedMemory = :memory, updatedAt = :now WHERE id = :projectId")
    suspend fun updateMemory(projectId: Long, memory: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE projects SET manualContext = :context, updatedAt = :now WHERE id = :projectId")
    suspend fun updateManualContext(projectId: Long, context: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE projects SET pinnedMemories = :pinned, updatedAt = :now WHERE id = :projectId")
    suspend fun updatePinnedMemories(projectId: Long, pinned: List<String>, now: Long = System.currentTimeMillis())
}
