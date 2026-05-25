package com.oli.projectsai.core.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** How a transcription was produced — drives the source chip shown in history. */
enum class TranscriptionSource { SHORT_FORM, LONG_FORM }

/**
 * A saved voice transcription. Global by design (no chat/project foreign key) so the history is one
 * searchable list across the whole app; [projectId] is an optional tag set when a transcript is sent
 * to a project. [title] starts as a few words derived from the text and is replaced by an AI title
 * once the model produces one; [summary] is generated on demand from the detail screen.
 */
@Entity(
    tableName = "transcriptions",
    indices = [Index("createdAt"), Index("projectId")]
)
data class Transcription(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val title: String = "",
    val summary: String? = null,
    val projectId: Long? = null,
    val source: TranscriptionSource = TranscriptionSource.SHORT_FORM,
    /** Original file name for long-form uploads; null for mic captures. */
    val audioFileName: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val deletedAt: Long? = null
)
