package com.oli.projectsai.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.oli.projectsai.data.db.entity.LinkedInSuggestion
import com.oli.projectsai.data.db.entity.SuggestionStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface LinkedInSuggestionDao {
    @Query(
        """
        SELECT * FROM linkedin_suggestions
        WHERE status = 'PENDING'
        ORDER BY score DESC, createdAt DESC
        """
    )
    fun pending(): Flow<List<LinkedInSuggestion>>

    @Query("SELECT * FROM linkedin_suggestions WHERE id = :id")
    suspend fun getById(id: Long): LinkedInSuggestion?

    @Query("SELECT * FROM linkedin_suggestions WHERE urn = :urn LIMIT 1")
    suspend fun getByUrn(urn: String): LinkedInSuggestion?

    /**
     * IGNORE on conflict: an URN that's already in the table — pending or otherwise — keeps
     * its existing row, so a re-fetch doesn't resurrect rejected suggestions or clobber the
     * user's edits.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfNew(suggestion: LinkedInSuggestion): Long

    @Query(
        """
        UPDATE linkedin_suggestions
        SET status = :status, errorMessage = :errorMessage, updatedAt = :now
        WHERE id = :id
        """
    )
    suspend fun setStatus(
        id: Long,
        status: SuggestionStatus,
        errorMessage: String? = null,
        now: Long = System.currentTimeMillis()
    )

    @Query(
        """
        UPDATE linkedin_suggestions
        SET suggestedComment = :comment, updatedAt = :now
        WHERE id = :id
        """
    )
    suspend fun updateComment(
        id: Long,
        comment: String,
        now: Long = System.currentTimeMillis()
    )

    /** Trim history so pending pulls don't slow down over time. */
    @Query("DELETE FROM linkedin_suggestions WHERE status != 'PENDING' AND updatedAt < :before")
    suspend fun pruneResolvedOlderThan(before: Long)
}
