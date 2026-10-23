package com.callnotes.util

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.callnotes.R
import com.callnotes.ui.MainActivity

/**
 * Every notification the app shows: recording in progress, one per background operation
 * (with Cancel / Stop all), new tasks, task reminders, and problems. Channels: "recording"
 * (quiet, ongoing), "alerts" (problems, speaker prompt), "tasks" (new tasks), "reminders"
 * (pops up on screen, with Done / Snooze). With the app lock on, task and processing
 * notifications hide their text on the lock screen.
 */
object Notifications {

    private const val CHANNEL_RECORDING = "recording"
    private const val CHANNEL_ALERTS = "alerts"
    private const val CHANNEL_TASKS = "tasks"
    /** High importance: a reminder pops up over whatever is on screen (heads-up). */
    private const val CHANNEL_REMINDERS = "reminders"

    const val ID_RECORDING = 1
    /** Transcription in progress. */
    const val ID_TRANSCRIBE = 4
    /** Summary in progress. */
    const val ID_SUMMARY = 5
    private const val ID_SPEAKER = 2
    private const val ID_PROBLEM = 3

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RECORDING, "הקלטה פעילה", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "התראות הקלטה", NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TASKS, "משימות חדשות", NotificationManager.IMPORTANCE_DEFAULT)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_REMINDERS, "תזכורות למשימות", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "קופצות בזמן שנקבע למשימה, עם \"בוצע\" ו\"נודניק\"."
            }
        )
    }

    /** The ongoing notification shown by [com.callnotes.capture.RecordingService]. */
    fun recording(context: Context, label: String?): Notification =
        builder(context, CHANNEL_RECORDING, "מקליט: ${label ?: "שיחה"}", "ההקלטה תופיע באפליקציה בסיום השיחה")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    /**
     * One background operation (transcribing or summarising one call). Shown while it runs
     * and removed when it ends. [callId] adds a cancel button for that call and opens it on tap.
     */
    fun work(
        context: Context,
        id: Int,
        title: String,
        text: String,
        done: Int = 0,
        total: Int = 0,
        callId: Long? = null,
    ): Notification {
        val b = NotificationCompat.Builder(context, CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp(context, callId, id))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setProgress(if (total > 0) total else 0, if (total > 0) done else 0, total <= 0)
        hideOnLockScreen(context, b, CHANNEL_RECORDING, if (id == ID_SUMMARY) "מסכם שיחה" else "מתמלל שיחה")
        if (callId != null) b.addAction(0, "ביטול", serviceAction(context, ACTION_CANCEL, id * 10 + 1, callId))
        b.addAction(0, "עצירת הכל", serviceAction(context, com.callnotes.processing.TranscriptionService.ACTION_STOP, id * 10 + 2, null))
        return b.build()
    }

    /** "דני · 21.10 14:05" — who the call was with, or what kind of call, and when. */
    fun callLabel(call: com.callnotes.data.CallEntity): String {
        val who = com.callnotes.data.Caller.label(call.contactName, call.number, call.kind, call.incoming)
        val time = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.US).format(java.util.Date(call.startedAt))
        return "$who · $time"
    }

    /**
     * A new task from a summary. Stays until tapped or dismissed; the tap opens the tasks
     * screen at this task.
     */
    fun newTask(context: Context, task: com.callnotes.data.TaskEntity, callLabel: String) {
        val due = task.dueDate?.let {
            runCatching { java.time.LocalDate.parse(it).format(java.time.format.DateTimeFormatter.ofPattern("dd.MM")) }.getOrNull()
        }
        val text = listOfNotNull(callLabel, due?.let { "עד $it" + (task.dueTime?.let { t -> " $t" } ?: "") }, if (!task.certain) "לא בטוח" else null).joinToString(" · ")
        postTask(context, task, "משימה חדשה: ${task.title}", text, publicTitle = "משימה חדשה")
    }

    /**
     * A task's reminder: pops up at the time it is due (or was due while the phone was off),
     * with "Done" and "Snooze" buttons. Tap opens it in the tasks screen.
     */
    fun taskDue(context: Context, task: com.callnotes.data.TaskEntity, callLabel: String?) {
        val text = listOfNotNull(dueText(task), callLabel).joinToString(" · ")
        val snooze = Prefs(context).snoozeMinutes
        val b = taskBuilder(context, CHANNEL_REMINDERS, task, "תזכורת: ${task.title}", text, publicTitle = "תזכורת למשימה")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "בוצע", taskAction(context, task.id, com.callnotes.util.TaskReminders.ACTION_DONE, 1))
            .addAction(0, "נודניק · ${snoozeLabel(snooze)}", taskAction(context, task.id, com.callnotes.util.TaskReminders.ACTION_SNOOZE, 2))
        post(context, taskNotificationId(task.id), b)
    }

    /** "עכשיו · 10:00", "להיום", "עבר המועד · 21.10". */
    private fun dueText(task: com.callnotes.data.TaskEntity): String {
        val date = task.dueDate?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
        val today = java.time.LocalDate.now()
        return when {
            date == null -> "תזכורת"
            date.isBefore(today) -> "עבר המועד · ${date.format(java.time.format.DateTimeFormatter.ofPattern("d.M"))}" +
                (task.dueTime?.let { " $it" } ?: "")
            task.dueTime != null -> (if (date == today) "היום" else date.format(java.time.format.DateTimeFormatter.ofPattern("d.M"))) +
                " · ${task.dueTime}"
            else -> "להיום"
        }
    }

    /** "10 דק׳", "שעה". */
    fun snoozeLabel(minutes: Int): String = when {
        minutes == 60 -> "שעה"
        minutes % 60 == 0 -> "${minutes / 60} שעות"
        else -> "$minutes דק׳"
    }

    private fun taskAction(context: Context, taskId: Long, action: String, slot: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, (taskId * 4 + slot).toInt(),
            Intent(context, com.callnotes.util.TaskReminders.ActionReceiver::class.java)
                .setAction(action)
                .putExtra(com.callnotes.util.TaskReminders.EXTRA_TASK_ID, taskId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** One notification per task (new or due — the later replaces the earlier); tap opens it. */
    private fun postTask(context: Context, task: com.callnotes.data.TaskEntity, title: String, text: String, publicTitle: String) =
        post(context, taskNotificationId(task.id), taskBuilder(context, CHANNEL_TASKS, task, title, text, publicTitle))

    private fun taskBuilder(
        context: Context, channel: String, task: com.callnotes.data.TaskEntity, title: String, text: String, publicTitle: String,
    ) = NotificationCompat.Builder(context, channel)
        .also { hideOnLockScreen(context, it, channel, publicTitle) }
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentTitle(title)
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(task.title + "\n" + text))
        .setContentIntent(openTask(context, task.id))
        .setAutoCancel(true)
        .setGroup("tasks")
        .setCategory(NotificationCompat.CATEGORY_REMINDER)

    /** Removes the new-task / reminder notification of a task (it was ticked done). */
    fun cancelTask(context: Context, taskId: Long) =
        NotificationManagerCompat.from(context).cancel(taskNotificationId(taskId))

    private fun taskNotificationId(taskId: Long) = (20_000 + taskId).toInt()

    private fun openTask(context: Context, taskId: Long): PendingIntent = PendingIntent.getActivity(
        context, taskNotificationId(taskId),
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_OPEN_TASK, taskId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** With the app lock on, the lock screen shows only [publicTitle], not names or tasks. */
    private fun hideOnLockScreen(context: Context, b: NotificationCompat.Builder, channel: String, publicTitle: String) {
        if (!Prefs(context).appLock) return
        b.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, channel)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle(publicTitle)
                    .build()
            )
    }

    const val EXTRA_OPEN_TASK = "open_task_id"
    const val ACTION_CANCEL = "com.callnotes.action.CANCEL_CALL"
    const val EXTRA_CALL_ID = "call_id"
    const val EXTRA_OPEN_CALL = "open_call_id"

    private fun serviceAction(context: Context, action: String, requestCode: Int, callId: Long?): PendingIntent =
        PendingIntent.getService(
            context, requestCode,
            Intent(context, com.callnotes.processing.TranscriptionService::class.java)
                .setAction(action)
                .apply { if (callId != null) putExtra(EXTRA_CALL_ID, callId) },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun cancelSpeakerPrompt(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID_SPEAKER)
    }

    fun askForSpeaker(context: Context) = post(
        context, ID_SPEAKER,
        builder(
            context, CHANNEL_ALERTS,
            "כדאי להפעיל רמקול",
            "בלי רמקול ההקלטה תקלוט בעיקר את הצד שלך בשיחה."
        ).setPriority(NotificationCompat.PRIORITY_HIGH).setTimeoutAfter(15_000)
    )

    fun problem(context: Context, title: String, text: String) = post(
        context, ID_PROBLEM,
        builder(context, CHANNEL_ALERTS, title, text).setPriority(NotificationCompat.PRIORITY_HIGH)
    )

    private fun builder(context: Context, channel: String, title: String, text: String) =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(context, null, 0))

    private fun openApp(context: Context, callId: Long?, requestCode: Int): PendingIntent = PendingIntent.getActivity(
        context, 100 + requestCode,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply { if (callId != null) putExtra(EXTRA_OPEN_CALL, callId) },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun post(context: Context, id: Int, builder: NotificationCompat.Builder) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        NotificationManagerCompat.from(context).notify(id, builder.build())
    }
}
