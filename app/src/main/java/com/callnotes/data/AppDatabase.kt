package com.callnotes.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// Encryption (SQLCipher) is planned for the MVP; the prototype keeps plain Room.
@Database(entities = [CallEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun calls(): CallDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "callnotes.db").build()
    }
}
