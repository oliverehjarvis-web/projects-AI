package com.oli.projectsai.core.db.dao

import androidx.room.*
import com.oli.projectsai.core.db.entity.Transcription
import kotlinx.coroutines.flow.Flow

@Dao
interface TranscriptionDao {
    @Query("SELECT * FROM transcriptions WHERE deletedAt IS NULL ORDER BY createdAt DESC")
    fun getAllFlow(): Flow<List<Transcription>>

    /**
     * Reactive substring search over title and body. A blank [query] matches everything, so the
     * history screen can drive both the full list and filtered results from one Flow. `LIKE` is
     * fast enough for a single user's transcript volume and avoids an FTS migration — same call as
     * [MessageDao.searchMessages].
     */
    @Query(
        """
        SELECT * FROM transcriptions
        WHERE deletedAt IS NULL
          AND (
            :query = ''
            OR title LIKE '%' || :query || '%'
            OR text LIKE '%' || :query || '%'
          )
        ORDER BY createdAt DESC
        """
    )
    fun searchFlow(query: String): Flow<List<Transcription>>

    @Query("SELECT * FROM transcriptions WHERE id = :id AND deletedAt IS NULL")
    fun getByIdFlow(id: Long): Flow<Transcription?>

    @Query("SELECT * FROM transcriptions WHERE id = :id AND deletedAt IS NULL")
    suspend fun getById(id: Long): Transcription?

    @Insert
    suspend fun insert(transcription: Transcription): Long

    @Query("UPDATE transcriptions SET title = :title, updatedAt = :now WHERE id = :id")
    suspend fun updateTitle(id: Long, title: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE transcriptions SET summary = :summary, updatedAt = :now WHERE id = :id")
    suspend fun updateSummary(id: Long, summary: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE transcriptions SET projectId = :projectId, updatedAt = :now WHERE id = :id")
    suspend fun updateProjectId(id: Long, projectId: Long?, now: Long = System.currentTimeMillis())

    @Query("UPDATE transcriptions SET deletedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: Long, now: Long = System.currentTimeMillis())
}
