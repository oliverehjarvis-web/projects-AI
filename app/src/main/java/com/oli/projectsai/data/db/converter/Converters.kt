package com.oli.projectsai.data.db.converter

import androidx.room.TypeConverter
import com.oli.projectsai.data.db.entity.MessageRole
import com.oli.projectsai.data.db.entity.PreferredBackend

class Converters {
    @TypeConverter
    fun fromStringList(value: List<String>): String = value.joinToString("\u001F")

    @TypeConverter
    fun toStringList(value: String): List<String> =
        if (value.isEmpty()) emptyList() else value.split("\u001F")

    @TypeConverter
    fun fromMessageRole(value: MessageRole): String = value.name

    @TypeConverter
    fun toMessageRole(value: String): MessageRole = MessageRole.valueOf(value)

    @TypeConverter
    fun fromPreferredBackend(value: PreferredBackend): String = value.name

    @TypeConverter
    fun toPreferredBackend(value: String): PreferredBackend = PreferredBackend.valueOf(value)
}
