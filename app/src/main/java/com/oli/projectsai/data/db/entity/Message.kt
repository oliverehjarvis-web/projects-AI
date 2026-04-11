package com.oli.projectsai.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class MessageRole { USER, ASSISTANT, SYSTEM }

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = Chat::class,
            parentColumns = ["id"],
            childColumns = ["chatId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("chatId")]
)
data class Message(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chatId: Long,
    val role: MessageRole,
    val content: String,
    val tokenCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)
