package com.oli.projectsai.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    version = 3,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao
    abstract fun quickActionDao(): QuickActionDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Messages gain attachmentPaths: stored as a unit-separator-joined string
                // (see Converters). Default empty string = empty list.
                db.execSQL(
                    "ALTER TABLE messages ADD COLUMN attachmentPaths TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE chats ADD COLUMN webSearchEnabled INTEGER NOT NULL DEFAULT 0"
                )
            }
        }
    }
}
