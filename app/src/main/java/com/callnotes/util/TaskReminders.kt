package com.callnotes.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.callnotes.CallNotesApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Due-date reminders: on the morning a task is due (09:00) a notification opens it in the
 * tasks screen. One inexact alarm is kept for the next due task (no exact-alarm permission
 * needed; Android may deliver it a few minutes late). Re-planned after every summary, every
 * tick, at boot, after an update and when the clock or time zone changes.
 */
object TaskReminders {

    const val HOUR = 9
    private const val ACTION_FIRE = "com.callnotes.action.TASK_REMINDER"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Re-plans in the background (safe to call from the UI thread). */
    fun reschedule(context: Context) {
        val app = context.applicationContext
        scope.launch { plan(app) }
    }

    /** Shows reminders that are due now, then sets the alarm for the next one. */
    suspend fun plan(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = alarmIntent(context)
        am.cancel(pi)
        if (!Prefs(context).taskReminders) return
        val dao = (context.applicationContext as CallNotesApp).database.calls()
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone)
        // Due today once it's past the reminder hour, or any earlier date (missed while off).
        val lastDue = if (now.toLocalTime() >= LocalTime.of(HOUR, 0)) now.toLocalDate() else now.toLocalDate().minusDays(1)
        val due = dao.dueUnreminded(lastDue.toString())
        if (due.isNotEmpty()) {
            due.forEach { t ->
                val call = dao.get(t.callId)
                Notifications.taskDue(context, t, call?.let(Notifications::callLabel))
            }
            dao.markReminded(due.map { it.id }, System.currentTimeMillis())
        }
        val next = dao.nextReminder() ?: return
        val date = runCatching { LocalDate.parse(next.dueDate) }.getOrNull() ?: return
        val at = date.atTime(HOUR, 0).atZone(zone).toInstant().toEpochMilli()
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.coerceAtLeast(System.currentTimeMillis() + 60_000), pi)
    }

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, Receiver::class.java).setAction(ACTION_FIRE),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** The alarm itself, plus boot / update / clock changes (all just re-plan). */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pending = goAsync()
            val app = context.applicationContext
            scope.launch {
                try { plan(app) } finally { pending.finish() }
            }
        }
    }
}
