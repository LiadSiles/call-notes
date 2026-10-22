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

/** What a tap on a task does, shared by the tasks screen and the call screen. */
object TaskActions {

    /** Ticks (or unticks) a task and its repeats in other calls; clears its notification. */
    suspend fun setDone(context: Context, task: TaskEntity, done: Boolean) {
        val dao = (context.applicationContext as CallNotesApp).database.calls()
        dao.setTaskDone(task.id, done)
        if (done) Notifications.cancelTask(context, task.id)
        TaskReminders.plan(context)
    }

    /** "Not the same task": shows it on its own again. */
    suspend fun unlinkDuplicate(context: Context, task: TaskEntity) {
        (context.applicationContext as CallNotesApp).database.calls().clearDuplicate(task.id)
        TaskReminders.plan(context)
    }

    const val GOOGLE_CALENDAR = "com.google.android.calendar"

    /**
     * Opens Google Calendar's "new event" screen filled in with the task — an all-day event
     * on its due date (or a blank date to pick). Nothing is saved until the user taps Save
     * there. Any other calendar app is used if Google Calendar isn't installed.
     */
    fun addToCalendar(context: Context, task: TaskEntity, callLabel: String?) {
        val intent = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, task.title)
            .putExtra(CalendarContract.Events.DESCRIPTION, listOfNotNull(
                callLabel?.let { "משיחה: $it" },
                task.duePhrase?.let { "מועד שנאמר: $it" },
            ).joinToString("\n"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        task.dueDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.let { d ->
            // All-day events are given in UTC midnight-to-midnight.
            val start = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            intent.putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start + 86_400_000L)
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
