package com.callnotes.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// Encryption (SQLCipher) is planned for 0.8; the prototype keeps plain Room.
@Database(entities = [CallEntity::class, SegmentEntity::class, TaskEntity::class], version = 7, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun calls(): CallDao

    companion object {
        /** 0.2.1: internet-call support adds the call kind. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE calls ADD COLUMN kind TEXT NOT NULL DEFAULT 'cellular'")
            }
        }

        /** 0.2.3: whether the microphone foreground service was running. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE calls ADD COLUMN foreground INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** 0.3: transcripts. */
        /** Tasks: source lines, certainty, creation time. */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN lines TEXT")
                db.execSQL("ALTER TABLE tasks ADD COLUMN certain INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE tasks ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Summary and tasks (Gemma). */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE calls ADD COLUMN analysisStatus TEXT")
                db.execSQL("ALTER TABLE calls ADD COLUMN analysisJson TEXT")
                db.execSQL("ALTER TABLE calls ADD COLUMN summaryTitle TEXT")
                db.execSQL("ALTER TABLE calls ADD COLUMN analysisModel TEXT")
                db.execSQL("ALTER TABLE calls ADD COLUMN analysisError TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tasks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`callId` INTEGER NOT NULL, `idx` INTEGER NOT NULL, `title` TEXT NOT NULL, " +
                        "`owner` TEXT NOT NULL, `dueDate` TEXT, `duePhrase` TEXT, `quote` TEXT, " +
                        "`done` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tasks_callId` ON `tasks` (`callId`)")
            }
        }

        /** Speakers: who said each segment, and the two voices per call. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE segments ADD COLUMN speaker INTEGER")
                db.execSQL("ALTER TABLE calls ADD COLUMN speakerEmbeddings BLOB")
                db.execSQL("ALTER TABLE calls ADD COLUMN meSpeaker INTEGER")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE calls ADD COLUMN transcriptModel TEXT")
                db.execSQL("ALTER TABLE calls ADD COLUMN error TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `segments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`callId` INTEGER NOT NULL, `idx` INTEGER NOT NULL, `startMs` INTEGER NOT NULL, " +
                        "`endMs` INTEGER NOT NULL, `text` TEXT NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_segments_callId` ON `segments` (`callId`)")
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "callnotes.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                .build()
    }
}
