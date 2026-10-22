package com.callnotes.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.callnotes.CallNotesApp
import com.callnotes.capture.CallTrace
import com.callnotes.processing.ProcessingLog
import com.callnotes.processing.ProcessingStatus
import java.io.File

/**
 * Automatic deletion, as the user sets it (both off by default):
 *  - recordings of calls already transcribed, after N days (the transcript and summary stay);
 *  - whole calls (recording, transcript, summary, tasks) after N days — except calls that
 *    still have an open task, which stay until the task is ticked.
 * Runs once a day (inexact alarm), at app start and when a setting changes.
 */
object Retention {

    /** Choices offered in settings, in days; 0 = never. */
    val AUDIO_DAYS = listOf(0, 1, 7, 30, 90, 365)
    val CALL_DAYS = listOf(0, 30, 90, 180, 365, 730)

    private const val DAY = 86_400_000L
    const val ACTION_DAILY = "com.callnotes.action.DAILY"

    fun scheduleDaily(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            context, 1,
            Intent(context, TaskReminders.Receiver::class.java).setAction(ACTION_DAILY),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        am.setInexactRepeating(AlarmManager.RTC, System.currentTimeMillis() + AlarmManager.INTERVAL_HOUR,
            AlarmManager.INTERVAL_DAY, pi)
    }

    /** Returns (recordings deleted, calls deleted). */
    suspend fun run(context: Context): Pair<Int, Int> {
        val prefs = Prefs(context)
        val dao = (context.applicationContext as CallNotesApp).database.calls()
        val now = System.currentTimeMillis()
        val busy = setOfNotNull(ProcessingStatus.state.value.callId, ProcessingStatus.summary.value.callId)
        var audio = 0
        var calls = 0
        if (prefs.callRetentionDays > 0) {
            for (c in dao.callsToExpire(now - prefs.callRetentionDays * DAY)) {
                if (c.id in busy) continue
                c.filePath?.let { deleteAudioFiles(it) }
                dao.delete(c.id)
                calls++
            }
        }
        if (prefs.audioRetentionDays > 0) {
            for (c in dao.audioToExpire(now - prefs.audioRetentionDays * DAY)) {
                if (c.id in busy) continue
                c.filePath?.let { deleteAudioFiles(it) }
                dao.markAudioDeleted(c.id, now)
                audio++
            }
        }
        if (audio + calls > 0) ProcessingLog.write(context, "retention: deleted $audio recordings, $calls calls")
        return audio to calls
    }

    private fun deleteAudioFiles(path: String) {
        File(path).delete()
        CallTrace.fileFor(File(path)).delete()
    }
}
