package com.callnotes.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.callnotes.CallNotesApp
import com.callnotes.data.TaskEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Task reminders: a pop-up notification at the time a task is due — its time of day, or
 * [HOUR]:00 on its date when no time was set — with "Done" and "Snooze" buttons. Snooze
 * shows it again after the chosen number of minutes, as many times as wanted.
 *
 * One alarm is kept, for the next reminder. It is exact when Android allows it (always
 * below Android 12; from 12 with "Alarms & reminders" access, which the user can grant or
 * take back in settings), otherwise inexact and possibly a few minutes late. Re-planned
 * whenever tasks change, at boot, after an update, when the clock or time zone changes
 * and when exact-alarm access changes.
 */
object TaskReminders {

    /** Reminder time for a task with a date but no time of day. */
    const val HOUR = 9
    private const val ACTION_FIRE = "com.callnotes.action.TASK_REMINDER"
    /** AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED (API 31). */
    private const val ACTION_EXACT_CHANGED = "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
    private val HANDLED = setOf(
        ACTION_FIRE, Retention.ACTION_DAILY, ACTION_EXACT_CHANGED,
        Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
        Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Whether reminders can ring on the minute (see the class comment). */
    fun exactAllowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true

    /** Re-plans in the background (safe to call from the UI thread). */
    fun reschedule(context: Context) {
        val app = context.applicationContext
        scope.launch { plan(app) }
    }

    /** Shows the reminders that are due now, then sets the alarm for the next one. */
    suspend fun plan(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = alarmIntent(context)
        am.cancel(pi)
        if (!Prefs(context).taskReminders) return
        val dao = (context.applicationContext as CallNotesApp).database.calls()
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val pending = dao.reminderCandidates().mapNotNull { t -> reminderAt(t, zone, HOUR)?.let { t to it } }
        // Due now, or missed while the phone was off.
        val due = pending.filter { it.second <= now }.map { it.first }
        if (due.isNotEmpty()) {
            due.forEach { t ->
                val call = if (t.standalone) null else dao.get(t.callId)
                Notifications.taskDue(context, t, call?.let(Notifications::callLabel))
            }
            dao.markReminded(due.map { it.id }, now)
        }
        val next = pending.filter { it.second > now }.minOfOrNull { it.second } ?: return
        if (exactAllowed(context)) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
                return
            } catch (_: SecurityException) {
                // Access was taken away between the check and the call: inexact below.
            }
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
    }

    /** "Snooze" on a reminder: shows it again in the chosen number of minutes. */
    suspend fun snooze(context: Context, taskId: Long) {
        val dao = (context.applicationContext as CallNotesApp).database.calls()
        val minutes = Prefs(context).snoozeMinutes
        dao.snooze(taskId, System.currentTimeMillis() + minutes * 60_000L)
        Notifications.cancelTask(context, taskId)
        plan(context)
    }

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, Receiver::class.java).setAction(ACTION_FIRE),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** The alarm itself, plus boot / update / clock changes / exact-alarm access (all re-plan). */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action !in HANDLED) return // exported for system broadcasts: ignore anything else
            val pending = goAsync()
            val app = context.applicationContext
            scope.launch {
                try {
                    plan(app)
                    // Clean-up on the daily alarm, at boot and after an update — not on every reminder.
                    if (intent.action != ACTION_FIRE) Retention.run(app)
                } finally { pending.finish() }
            }
        }
    }

    const val ACTION_DONE = "com.callnotes.action.TASK_DONE"
    const val ACTION_SNOOZE = "com.callnotes.action.TASK_SNOOZE"
    const val EXTRA_TASK_ID = "task_id"

    /** The "Done" and "Snooze" buttons on a reminder (not exported: only our notifications). */
    class ActionReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(EXTRA_TASK_ID, -1L).takeIf { it > 0 } ?: return
            val pending = goAsync()
            val app = context.applicationContext
            scope.launch {
                try {
                    when (intent.action) {
                        ACTION_SNOOZE -> snooze(app, id)
                        ACTION_DONE -> {
                            val dao = (app as CallNotesApp).database.calls()
                            dao.getTask(id)?.let { TaskActions.setDone(app, it, true) }
                                ?: Notifications.cancelTask(app, id)
                        }
                    }
                } finally { pending.finish() }
            }
        }
    }
}

/**
 * When [task]'s reminder should show (epoch ms): the snooze time if snoozed; else, if not
 * reminded yet, its date at its time of day (or [hour]:00). Null = nothing to show.
 */
fun reminderAt(task: TaskEntity, zone: ZoneId, hour: Int): Long? {
    task.snoozedUntil?.let { return it }
    if (task.remindedAt != null) return null
    val date = task.dueDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
    val time = task.dueTime?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: LocalTime.of(hour, 0)
    return date.atTime(time).atZone(zone).toInstant().toEpochMilli()
}
