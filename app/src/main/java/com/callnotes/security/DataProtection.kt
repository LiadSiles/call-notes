package com.callnotes.security

import android.content.Context
import com.callnotes.CallNotesApp
import com.callnotes.data.CallStatus
import com.callnotes.processing.ProcessingLog
import com.callnotes.processing.ProcessingStatus
import com.callnotes.util.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Optional extra encryption ("הצפנה נוספת"), off by default. On: the database is SQLCipher
 * (converted at the next app start) and recordings are sealed ([SealedFile]) once they are
 * processed — a recording in progress or waiting for transcription stays a plain file in
 * the app's private storage until then. Off: everything is converted back. Android already
 * encrypts app storage with the phone's lock; this adds protection for a copied data folder
 * or a rooted / forensically imaged phone.
 */
object DataProtection {

    enum class Db { PLAIN, ENCRYPTED }

    data class Progress(val running: Boolean = false, val done: Int = 0, val total: Int = 0)

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress
    private val lock = Mutex()

    /** Called by AppDatabase.build before Room opens the file. */
    fun prepareDatabase(context: Context, db: File): Db {
        val prefs = Prefs(context)
        val want = prefs.encryptData
        DbCrypt.recover(db)
        if (DbCrypt.isEncrypted(db)) {
            val key = runCatching { String(KeyVault.sqlKey(context), Charsets.US_ASCII) }.getOrNull()
            if (key == null || !DbCrypt.canOpen(db, key)) {
                setAside(context, db)
                return if (want) Db.ENCRYPTED else Db.PLAIN
            }
            if (want) return Db.ENCRYPTED
            return runCatching { DbCrypt.decrypt(db, key); Db.PLAIN }.getOrElse { e ->
                ProcessingLog.write(context, "database decrypt FAILED, staying encrypted: ${e.javaClass.simpleName}")
                Db.ENCRYPTED
            }
        }
        if (!want) return Db.PLAIN
        val key = runCatching { String(KeyVault.sqlKey(context), Charsets.US_ASCII) }.getOrElse { e ->
            ProcessingLog.write(context, "no data key (${e.javaClass.simpleName}); database stays plain")
            return Db.PLAIN
        }
        if (!db.exists()) return Db.ENCRYPTED // Room creates it encrypted
        return runCatching { DbCrypt.encrypt(db, key); Db.ENCRYPTED }.getOrElse { e ->
            ProcessingLog.write(context, "database encrypt FAILED, staying plain: ${e.javaClass.simpleName}")
            Db.PLAIN
        }
    }

    /** The data is unreadable without the lost key: keep the file aside, start fresh. */
    private fun setAside(context: Context, db: File) {
        val stamp = System.currentTimeMillis()
        listOf("", "-wal", "-shm").forEach { File(db.path + it).renameTo(File("${db.path}$it.unreadable-$stamp")) }
        File(context.filesDir, "recordings").listFiles { f -> SealedFile.isSealed(f.name) }?.forEach { it.delete() }
        KeyVault.reset(context)
        Prefs(context).dataKeyLost = true
        ProcessingLog.write(context, "encrypted database could not be opened (key lost); set aside, starting fresh")
    }

    /** Database file matches the setting (false until the app restarts after a change). */
    fun databaseMatchesSetting(context: Context): Boolean =
        DbCrypt.isEncrypted(context.getDatabasePath(com.callnotes.data.AppDatabase.NAME)) == Prefs(context).encryptData

    /** Seals (or unseals) one call's recording to match the setting. Skips busy calls. */
    suspend fun applyToCall(context: Context, callId: Long, force: Boolean = false): Unit =
        lock.withLock { convertCall(context, callId, force) }

    /** Brings every recording in line with the setting; resumable (app start, after a change). */
    suspend fun applyToAll(context: Context) = lock.withLock {
        val dao = (context.applicationContext as CallNotesApp).database.calls()
        val want = Prefs(context).encryptData
        val todo = dao.withRecording().filter { SealedFile.isSealed(it.filePath!!) != want }
        if (todo.isEmpty()) return@withLock
        _progress.value = Progress(true, 0, todo.size)
        var done = 0
        try {
            for (c in todo) {
                convertCall(context, c.id)
                _progress.value = Progress(true, ++done, todo.size)
            }
        } finally {
            _progress.value = Progress()
        }
    }

    private suspend fun convertCall(context: Context, callId: Long, force: Boolean = false) {
        val dao = (context.applicationContext as CallNotesApp).database.calls()
        val call = dao.get(callId) ?: return
        val path = call.filePath ?: return
        val want = Prefs(context).encryptData
        if (SealedFile.isSealed(path) == want) return
        // Not the call being transcribed (it reads the file); the next pass picks it up.
        if (!force && (call.status == CallStatus.TRANSCRIBING || callId == ProcessingStatus.state.value.callId)) return
        val file = File(path)
        if (!file.isFile) return
        try {
            val out = if (want) SealedFile.seal(context, file)
            else SealedFile.unseal(context, file, File(file.parentFile, SealedFile.plainName(file)))
            dao.setFilePath(callId, out.absolutePath)
            file.delete()
        } catch (e: Exception) {
            ProcessingLog.write(context, "call $callId: recording ${if (want) "encrypt" else "decrypt"} FAILED: ${e.javaClass.simpleName}")
        }
    }

    /**
     * The recording to decode for transcription: the file itself, or a plain temporary copy
     * of a sealed one (second = true: delete it after use). Taken under the conversion lock,
     * after the worker has published the call as busy, so it can't be sealed mid-read.
     */
    suspend fun sourceFor(context: Context, callId: Long): Pair<File, Boolean>? = lock.withLock {
        val path = (context.applicationContext as CallNotesApp).database.calls().get(callId)?.filePath ?: return@withLock null
        val f = File(path)
        if (SealedFile.isSealed(path)) plainCopy(context, f, "plain") to true else f to false
    }

    /**
     * A plain copy of a sealed recording in the cache, for decoding or sharing. The caller
     * deletes it when done; [clearCache] removes leftovers at app start.
     */
    fun plainCopy(context: Context, sealed: File, dir: String): File {
        val d = File(context.cacheDir, dir).apply { mkdirs() }
        return SealedFile.unseal(context, sealed, File(d, SealedFile.plainName(sealed)))
    }

    fun clearCache(context: Context) {
        listOf("plain", "share").forEach { File(context.cacheDir, it).deleteRecursively() }
    }
}
