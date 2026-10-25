package com.callnotes.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.callnotes.security.DataProtection
import com.callnotes.security.DbCrypt
import com.callnotes.security.KeyVault

/**
 * Room database `callnotes.db`. Schema changes are explicit migrations (never destructive);
 * optional SQLCipher encryption is set up in [build] via DataProtection.
 */
@Database(
    entities = [CallEntity::class, SegmentEntity::class, TaskEntity::class, VoiceMemoEntity::class],
    version = 12,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun calls(): CallDao

    companion object {
        /** Transcript lines: background speech set aside. */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE segments ADD COLUMN background INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Tasks: time of day, snooze, where it came from, edited by hand; voice notes. */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN dueTime TEXT")
                db.execSQL("ALTER TABLE tasks ADD COLUMN snoozedUntil INTEGER")
                db.execSQL("ALTER TABLE tasks ADD COLUMN source TEXT NOT NULL DEFAULT 'call'")
                db.execSQL("ALTER TABLE tasks ADD COLUMN edited INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `voice_memos` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `path` TEXT, `durationMs` INTEGER NOT NULL, " +
                        "`state` TEXT NOT NULL, `text` TEXT, `error` TEXT)"
                )
            }
        }

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

        /** Calls: battery and time used by transcription and summary. */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE calls ADD COLUMN transcribeEnergy REAL")
                db.execSQL("ALTER TABLE calls ADD COLUMN analyzeEnergy REAL")
                db.execSQL("ALTER TABLE calls ADD COLUMN transcribeMs INTEGER")
                db.execSQL("ALTER TABLE calls ADD COLUMN analyzeMs INTEGER")
            }
        }

        /** Calls: when retention deleted the recording. */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE calls ADD COLUMN audioDeletedAt INTEGER")
            }
        }

        /** Tasks: reminders and duplicates across calls. */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN remindedAt INTEGER")
                db.execSQL("ALTER TABLE tasks ADD COLUMN duplicateOf INTEGER")
            }
        }

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

        /** 0.3: transcripts. */
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

        const val NAME = "callnotes.db"

        fun build(context: Context): AppDatabase {
            val builder = Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
            when (DataProtection.prepareDatabase(context, context.getDatabasePath(NAME))) {
                DataProtection.Db.ENCRYPTED -> {
                    DbCrypt.loadLibrary()
                    builder.openHelperFactory(
                    net.zetetic.database.sqlcipher.SupportOpenHelperFactory(
                        KeyVault.sqlKey(context)
                    )
                )
                }
                DataProtection.Db.PLAIN -> Unit
            }
            return builder.build()
        }
    }
}
