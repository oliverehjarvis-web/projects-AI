package com.oli.projectsai.data.db.dao

import androidx.room.*
import com.oli.projectsai.data.db.entity.QuickAction
import kotlinx.coroutines.flow.Flow

@Dao
interface QuickActionDao {
    @Query("SELECT * FROM quick_actions WHERE projectId = :projectId ORDER BY sortOrder ASC")
    fun getByProject(projectId: Long): Flow<List<QuickAction>>

    @Query("SELECT * FROM quick_actions WHERE id = :id")
    suspend fun getById(id: Long): QuickAction?

    @Insert
    suspend fun insert(action: QuickAction): Long

    @Update
    suspend fun update(action: QuickAction)

    @Delete
    suspend fun delete(action: QuickAction)
}
