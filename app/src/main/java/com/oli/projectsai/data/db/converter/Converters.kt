package com.oli.projectsai.data.db.converter

import android.util.Log
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
    fun toMessageRole(value: String): MessageRole = try {
        MessageRole.valueOf(value)
    } catch (t: IllegalArgumentException) {
        Log.w(TAG, "Unknown MessageRole '$value', falling back to USER")
        MessageRole.USER
    }

    @TypeConverter
    fun fromPreferredBackend(value: PreferredBackend): String = value.name

    @TypeConverter
    fun toPreferredBackend(value: String): PreferredBackend = try {
        PreferredBackend.valueOf(value)
    } catch (t: IllegalArgumentException) {
        Log.w(TAG, "Unknown PreferredBackend '$value', falling back to LOCAL")
        PreferredBackend.LOCAL
    }

    private companion object {
        const val TAG = "Converters"
    }
}
