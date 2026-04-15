package com.oli.projectsai.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Deprecated("Reserved for future multi-backend support — not currently branched on in the UI.")
enum class PreferredBackend { LOCAL, REMOTE }

@Entity(tableName = "projects")
data class Project(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String = "",
    val manualContext: String = "",
    val accumulatedMemory: String = "",
    val pinnedMemories: List<String> = emptyList(),
    val preferredBackend: PreferredBackend = PreferredBackend.LOCAL,
    val memoryTokenLimit: Int = 8000,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
