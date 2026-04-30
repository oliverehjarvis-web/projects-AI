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
        QuickAction::class,
        LinkedInSuggestion::class
    ],
    version = 7,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao
    abstract fun quickActionDao(): QuickActionDao
    abstract fun linkedInSuggestionDao(): LinkedInSuggestionDao

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

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE projects ADD COLUMN contextLength INTEGER NOT NULL DEFAULT 16384"
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE projects ADD COLUMN isSecret INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE projects ADD COLUMN remoteId TEXT")
                db.execSQL("ALTER TABLE projects ADD COLUMN deletedAt INTEGER")
                db.execSQL("ALTER TABLE chats ADD COLUMN remoteId TEXT")
                db.execSQL("ALTER TABLE chats ADD COLUMN deletedAt INTEGER")
                db.execSQL("ALTER TABLE messages ADD COLUMN remoteId TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN deletedAt INTEGER")
                db.execSQL("ALTER TABLE quick_actions ADD COLUMN remoteId TEXT")
                db.execSQL("ALTER TABLE quick_actions ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE quick_actions ADD COLUMN deletedAt INTEGER")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS linkedin_suggestions (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        urn TEXT NOT NULL,
                        authorName TEXT NOT NULL,
                        authorHeadline TEXT NOT NULL,
                        postText TEXT NOT NULL,
                        postUrl TEXT NOT NULL,
                        suggestedAction TEXT NOT NULL,
                        suggestedComment TEXT,
                        score REAL NOT NULL,
                        status TEXT NOT NULL,
                        errorMessage TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_linkedin_suggestions_urn ON linkedin_suggestions(urn)"
                )
            }
        }
    }
}
