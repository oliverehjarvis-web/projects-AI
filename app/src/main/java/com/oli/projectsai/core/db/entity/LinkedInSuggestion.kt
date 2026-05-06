package com.oli.projectsai.core.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class SuggestedAction { LIKE, COMMENT, SKIP }

enum class SuggestionStatus { PENDING, APPROVED, REJECTED, FAILED }

@Entity(
    tableName = "linkedin_suggestions",
    indices = [Index(value = ["urn"], unique = true)]
)
data class LinkedInSuggestion(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val urn: String,
    val authorName: String,
    val authorHeadline: String,
    val postText: String,
    val postUrl: String,
    val suggestedAction: SuggestedAction,
    val suggestedComment: String?,
    val score: Float,
    val status: SuggestionStatus = SuggestionStatus.PENDING,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
