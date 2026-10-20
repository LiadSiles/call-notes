package com.callnotes.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// Encryption (SQLCipher) is planned for 0.8; the prototype keeps plain Room.
@Database(entities = [CallEntity::class, SegmentEntity::class], version = 4, exportSchema = false)
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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
    }
}
