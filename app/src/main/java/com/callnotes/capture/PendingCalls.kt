package com.callnotes.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.callnotes.CallNotesApp
import com.callnotes.data.CallEntity
import com.callnotes.processing.ProcessingLog
import com.callnotes.processing.TranscriptionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File

/**
 * A finished call on its way from the recording process to the database.
 *
 * Recording runs in its own small process (`:recorder`) so that the app's main process —
 * which holds Whisper and Gemma, gigabytes of memory, and may be closed by Android —
 * can die without taking the accessibility service down (Android doesn't start it again
 * by itself). The recording process doesn't open the database (opening it may convert
 * its encryption): it writes the call as a small JSON file and tells the main process,
 * which adds it to the database ([importAll]) — now, or at its next start, so no call is
 * lost if the main process isn't running.
 */
object PendingCalls {

    const val ACTION_SAVED = "com.callnotes.action.CALL_SAVED"
    private val lock = Mutex()

    private fun dir(context: Context) = File(context.filesDir, "pending_calls").apply { mkdirs() }

    /** Recording process: keeps [call] for the main process and wakes it. */
    fun save(context: Context, call: CallEntity) {
        val dir = dir(context)
        val tmp = File(dir, "${call.startedAt}.json.tmp")
        tmp.writeText(toJson(call).toString())
        tmp.renameTo(File(dir, "${call.startedAt}.json")) // complete files only
        context.sendBroadcast(Intent(context, Receiver::class.java).setAction(ACTION_SAVED))
    }

    /**
     * Main process: adds every waiting call to the database, oldest first, and removes its
     * file. A call already there (saved before a crash removed the file) isn't added twice.
     * Returns how many were added.
     */
    suspend fun importAll(context: Context): Int = lock.withLock {
        val files = dir(context).listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name } ?: return@withLock 0
        if (files.isEmpty()) return@withLock 0
        val dao = (context.applicationContext as CallNotesApp).database.calls()
        var added = 0
        for (f in files) {
            val call = runCatching { fromJson(JSONObject(f.readText())) }.getOrNull()
            if (call == null) {
                ProcessingLog.write(context, "pending call ${f.name} unreadable; kept aside")
                f.renameTo(File(f.path + ".bad"))
                continue
            }
            if (dao.countAt(call.startedAt, call.kind) == 0) { dao.insert(call); added++ }
            f.delete()
        }
        added
    }

    /** The main process's end of [save]: add the call, then start processing as usual. */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_SAVED) return
            val pending = goAsync()
            val app = context.applicationContext
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    if (importAll(app) > 0) TranscriptionService.start(app, afterCall = true)
                } finally {
                    pending.finish()
                }
            }
        }
    }

    internal fun toJson(c: CallEntity): JSONObject = JSONObject()
        .put("startedAt", c.startedAt)
        .put("endedAt", c.endedAt)
        .put("incoming", c.incoming)
        .put("number", c.number ?: JSONObject.NULL)
        .put("contactName", c.contactName ?: JSONObject.NULL)
        .put("filePath", c.filePath ?: JSONObject.NULL)
        .put("audioSource", c.audioSource ?: JSONObject.NULL)
        .put("peakLevel", c.peakLevel)
        .put("silenced", c.silenced)
        .put("speakerResult", c.speakerResult)
        .put("speakerMethod", c.speakerMethod ?: JSONObject.NULL)
        .put("status", c.status)
        .put("kind", c.kind)
        .put("foreground", c.foreground)

    internal fun fromJson(o: JSONObject): CallEntity {
        fun str(key: String): String? = if (o.isNull(key)) null else o.getString(key)
        return CallEntity(
            startedAt = o.getLong("startedAt"),
            endedAt = o.getLong("endedAt"),
            incoming = o.getBoolean("incoming"),
            number = str("number"),
            contactName = str("contactName"),
            filePath = str("filePath"),
            audioSource = str("audioSource"),
            peakLevel = o.getInt("peakLevel"),
            silenced = o.getBoolean("silenced"),
            speakerResult = o.getString("speakerResult"),
            speakerMethod = str("speakerMethod"),
            status = o.getString("status"),
            kind = o.getString("kind"),
            foreground = o.getBoolean("foreground"),
        )
    }
}
