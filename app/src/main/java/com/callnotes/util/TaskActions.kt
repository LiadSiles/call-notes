package com.callnotes.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.widget.Toast
import com.callnotes.CallNotesApp
import com.callnotes.data.TaskEntity
import java.time.LocalDate
import java.time.ZoneOffset

/** What the user can do with a task, shared by the tasks screen, the call screen and notifications. */
object TaskActions {

    /** Ticks (or unticks) a task and its repeats in other calls; clears its notification. */
    suspend fun setDone(context: Context, task: TaskEntity, done: Boolean) {
        val dao = (context.applicationContext as CallNotesApp).database.calls()
        dao.setTaskDone(task.id, done)
        if (done) Notifications.cancelTask(context, task.id)
        TaskReminders.plan(context)
    }

    /**
     * Saves a task the user typed in (id 0) or edited. An edited call task is marked so a
     * new summary of the call keeps it; a changed date or time re-arms its reminder.
     * Returns the task's id.
     */
    suspend fun save(context: Context, task: TaskEntity): Long {
        val dao = (context.applicationContext as CallNotesApp).database.calls()
        val id = if (task.id == 0L) {
            dao.insertTask(task.copy(createdAt = System.currentTimeMillis()))
        } else {
            val old = dao.getTask(task.id)
            val moved = old == null || old.dueDate != task.dueDate || old.dueTime != task.dueTime
            dao.updateTask(task.copy(
                edited = true,
                remindedAt = if (moved) null else task.remindedAt,
                snoozedUntil = if (moved) null else task.snoozedUntil,
            ))
            if (moved) Notifications.cancelTask(context, task.id)
            task.id
        }
        TaskReminders.plan(context)
        return id
    }

    /** Deletes a task (its reminder too). */
    suspend fun delete(context: Context, task: TaskEntity) {
        (context.applicationContext as CallNotesApp).database.calls().deleteTask(task.id)
        Notifications.cancelTask(context, task.id)
        TaskReminders.plan(context)
    }

    /** "Not the same task": shows it on its own again. */
    suspend fun unlinkDuplicate(context: Context, task: TaskEntity) {
        (context.applicationContext as CallNotesApp).database.calls().clearDuplicate(task.id)
        TaskReminders.plan(context)
    }

    const val GOOGLE_CALENDAR = "com.google.android.calendar"

    /**
     * Opens Google Calendar's "new event" screen filled in with the task — at its time (half
     * an hour), or an all-day event on its date, or a blank date to pick. Nothing is saved
     * until the user taps Save there. Any other calendar app is used if Google Calendar
     * isn't installed.
     */
    fun addToCalendar(context: Context, task: TaskEntity, callLabel: String?) {
        val intent = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, task.title)
            .putExtra(CalendarContract.Events.DESCRIPTION, listOfNotNull(
                callLabel?.let { "משיחה: $it" },
                task.quote?.takeIf { task.source == com.callnotes.data.TaskSource.VOICE }?.let { "מהקלטה: $it" },
                task.duePhrase?.let { "מועד שנאמר: $it" },
            ).joinToString("\n"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        task.dueDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.let { d ->
            val time = task.dueTime?.let { runCatching { java.time.LocalTime.parse(it) }.getOrNull() }
            if (time != null) {
                val start = d.atTime(time).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                intent.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
                    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start + 30 * 60_000L)
            } else {
                // All-day events are given in UTC midnight-to-midnight.
                val start = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                intent.putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
                    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
                    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start + 86_400_000L)
            }
        }
        try {
            context.startActivity(Intent(intent).setPackage(GOOGLE_CALENDAR))
        } catch (_: ActivityNotFoundException) {
            try {
                context.startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, "לא נמצאה אפליקציית יומן במכשיר", Toast.LENGTH_LONG).show()
            }
        }
    }
}
