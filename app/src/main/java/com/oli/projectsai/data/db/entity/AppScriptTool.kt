package com.oli.projectsai.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class AppScriptAuthMode { SHARED_SECRET, OAUTH }

@Entity(
    tableName = "app_script_tools",
    foreignKeys = [
        ForeignKey(
            entity = Project::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("projectId"),
        Index(value = ["projectId", "name"], unique = true)
    ]
)
data class AppScriptTool(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val name: String,
    val description: String,
    val argSchemaHint: String = "",
    val authMode: AppScriptAuthMode,
    val webAppUrl: String = "",
    val scriptId: String = "",
    val functionName: String = "",
    val secretRef: String? = null,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val remoteId: String? = null,
    val deletedAt: Long? = null
)
