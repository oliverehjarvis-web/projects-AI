package com.oli.projectsai.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.oli.projectsai.data.db.converter.Converters
import com.oli.projectsai.data.db.dao.*
import com.oli.projectsai.data.db.entity.*

@Database(
    entities = [
        Project::class,
        Chat::class,
        Message::class,
        QuickAction::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao
    abstract fun quickActionDao(): QuickActionDao
}
