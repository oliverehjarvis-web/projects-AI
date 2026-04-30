package com.oli.projectsai.data.db.dao

import androidx.room.*
import com.oli.projectsai.data.db.entity.AppScriptTool
import kotlinx.coroutines.flow.Flow

@Dao
interface AppScriptToolDao {
    @Query("SELECT * FROM app_script_tools WHERE projectId = :projectId AND deletedAt IS NULL ORDER BY name ASC")
    fun getByProject(projectId: Long): Flow<List<AppScriptTool>>

    @Query("SELECT * FROM app_script_tools WHERE projectId = :projectId AND deletedAt IS NULL ORDER BY name ASC")
    suspend fun getByProjectOnce(projectId: Long): List<AppScriptTool>

    @Query("SELECT * FROM app_script_tools WHERE id = :id AND deletedAt IS NULL")
    suspend fun getById(id: Long): AppScriptTool?

    @Insert
    suspend fun insert(tool: AppScriptTool): Long

    @Update
    suspend fun update(tool: AppScriptTool)

    @Query("UPDATE app_script_tools SET deletedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE app_script_tools SET deletedAt = :now, updatedAt = :now WHERE projectId = :projectId AND deletedAt IS NULL")
    suspend fun softDeleteByProject(projectId: Long, now: Long = System.currentTimeMillis())

    @Query("SELECT * FROM app_script_tools ORDER BY id ASC")
    suspend fun getAllForSync(): List<AppScriptTool>

    @Query("SELECT * FROM app_script_tools WHERE remoteId = :remoteId LIMIT 1")
    suspend fun getByRemoteId(remoteId: String): AppScriptTool?

    @Query("UPDATE app_script_tools SET remoteId = :remoteId WHERE id = :id")
    suspend fun updateRemoteId(id: Long, remoteId: String)
}
