package com.callnotes.security

import android.content.Context
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File

/**
 * Converts the Room database file between plain SQLite and SQLCipher (AES-256), before
 * Room opens it. Done with `sqlcipher_export` into a new file, then swapped in, so an
 * interruption leaves the original untouched.
 */
object DbCrypt {

    private val PLAIN_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    @Volatile private var loaded = false

    fun loadLibrary() {
        if (!loaded) { System.loadLibrary("sqlcipher"); loaded = true }
    }

    /** True if [db] exists and is not a plain SQLite file. */
    fun isEncrypted(db: File): Boolean {
        if (!db.isFile || db.length() < 16) return false
        val head = ByteArray(16)
        db.inputStream().use { it.read(head) }
        return !head.contentEquals(PLAIN_HEADER)
    }

    /** The key opens [db] (false: the key was lost or the file is damaged). */
    fun canOpen(db: File, key: String): Boolean = runCatching {
        loadLibrary()
        SQLiteDatabase.openOrCreateDatabase(db.path, key, null, null, null).use { d ->
            d.rawQuery("SELECT count(*) FROM sqlite_master", null).use { it.moveToFirst() }
        }
    }.getOrDefault(false)

    fun encrypt(db: File, key: String) = convert(db, fromKey = "", toKey = key)

    fun decrypt(db: File, key: String) = convert(db, fromKey = key, toKey = "")

    /** A swap interrupted between delete and rename leaves only the converted file. */
    fun recover(db: File) {
        val tmp = File(db.path + ".convert")
        if (!db.exists() && tmp.isFile) tmp.renameTo(db) else if (db.exists()) tmp.delete()
    }

    private fun convert(db: File, fromKey: String, toKey: String) {
        loadLibrary()
        val tmp = File(db.path + ".convert")
        tmp.delete()
        SQLiteDatabase.openOrCreateDatabase(db.path, fromKey, null, null, null).use { d ->
            val version = d.rawQuery("PRAGMA user_version", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
            val keyLiteral = if (toKey.isEmpty()) "''" else "\"$toKey\""
            d.execSQL("ATTACH DATABASE '${tmp.path.replace("'", "''")}' AS target KEY $keyLiteral")
            d.rawQuery("SELECT sqlcipher_export('target')", null).use { it.moveToFirst() }
            d.execSQL("PRAGMA target.user_version = $version")
            d.execSQL("DETACH DATABASE target")
        }
        check(tmp.isFile && tmp.length() > 0) { "export produced nothing" }
        listOf("-wal", "-shm", "-journal").forEach { File(db.path + it).delete() }
        check(db.delete() && tmp.renameTo(db)) { "swap failed" }
    }
}
