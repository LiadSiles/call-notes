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
import com.callnotes.data.CallEntity
import com.callnotes.data.Caller
import com.callnotes.data.TaskEntity
import com.callnotes.processing.Request
import com.callnotes.processing.Stage
import com.callnotes.processing.TranscriptionService
import com.callnotes.ui.MainActivity

/**
 * Every notification the app shows: recording in progress, one row per background operation
 * in progress or waiting in the queue (with Cancel; Stop all on those in progress), new
 * tasks, task reminders, and problems. Channels: "recording"
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
    /** Background processing starting, before (and between) rows for what it works on. */
    const val ID_SERVICE = 4
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
     * Notification id of the row for one request — a call or a voice note, in one stage — so
     * its waiting row and its progress row are the same row. Negative: clear of the task ids.
     */
    fun rowId(stage: Stage, callId: Long, memoId: Long): Int =
        -(1_000 + (if (memoId != 0L) memoId * 4 + 2 else callId * 4) + stage.ordinal).toInt()

    /**
     * The row of one operation in progress (transcribing or summarising a call, or a voice
     * note on its way to a task), removed when it ends. Cancel stops that one; tap opens it.
     */
    fun work(
        context: Context,
        id: Int,
        stage: Stage,
        title: String,
        text: String,
        done: Int = 0,
        total: Int = 0,
        callId: Long? = null,
        memoId: Long? = null,
    ): Notification {
        val b = row(context, id, title, text, callId)
            .setProgress(if (total > 0) total else 0, if (total > 0) done else 0, total <= 0)
        hideOnLockScreen(context, b, CHANNEL_RECORDING, if (stage == Stage.SUMMARY) "מסכם" else "מתמלל")
        if (callId != null || memoId != null) b.addAction(0, "ביטול", serviceAction(context, ACTION_CANCEL, id * 4 + 1, callId, memoId))
        b.addAction(0, "עצירת הכל", serviceAction(context, TranscriptionService.ACTION_STOP, id * 4 + 2, null))
        return b.build()
    }

    /**
     * The row of a request waiting its turn: what it is, its place in its queue, and a
     * cancel button. [sortTime] keeps the rows in queue order in the shade.
     */
    fun waiting(context: Context, id: Int, r: Request, callLabel: String?, place: Int, sortTime: Long): Notification {
        val what = if (r.stage == Stage.TRANSCRIBE) "תמלול" else "סיכום"
        val title = when {
            r.isMemo && r.stage == Stage.TRANSCRIBE -> "הקלטת משימה ממתינה לתמלול"
            r.isMemo -> "הקלטת משימה ממתינה ליצירת המשימה"
            else -> "ממתין ל$what: ${callLabel ?: "שיחה"}"
        }
        val text = if (place <= 1) "הבא בתור ה$what" else "מקום $place בתור ה$what"
        val b = row(context, id, title, text, r.callId.takeIf { !r.isMemo })
            .setWhen(sortTime)
            .setShowWhen(false)
        hideOnLockScreen(context, b, CHANNEL_RECORDING, "ממתין בתור")
        b.addAction(0, "ביטול", serviceAction(context, ACTION_CANCEL, id * 4 + 1,
            r.callId.takeIf { !r.isMemo }, r.memoId.takeIf { r.isMemo }))
        return b.build()
    }

    /** The service's row before it shows what it works on (and briefly between items). */
    fun service(context: Context): Notification {
        val b = row(context, ID_SERVICE, "מכין…", "", null).setProgress(0, 0, true)
        b.addAction(0, "עצירת הכל", serviceAction(context, TranscriptionService.ACTION_STOP, ID_SERVICE * 4 + 2, null))
        return b.build()
    }

    private fun row(context: Context, id: Int, title: String, text: String, callId: Long?) =
        NotificationCompat.Builder(context, CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp(context, callId, id))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

    /** "דני · 21.10 14:05" — who the call was with, or what kind of call, and when. */
    fun callLabel(call: CallEntity): String {
        val who = Caller.label(call.contactName, call.number, call.kind, call.incoming)
        val time = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.US).format(java.util.Date(call.startedAt))
        return "$who · $time"
    }

    /**
     * A new task from a summary. Stays until tapped or dismissed; the tap opens the tasks
     * screen at this task.
     */
    fun newTask(context: Context, task: TaskEntity, callLabel: String) {
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
    fun taskDue(context: Context, task: TaskEntity, callLabel: String?) {
        val text = listOfNotNull(dueText(task), callLabel).joinToString(" · ")
        val snooze = Prefs(context).snoozeMinutes
        val b = taskBuilder(context, CHANNEL_REMINDERS, task, "תזכורת: ${task.title}", text, publicTitle = "תזכורת למשימה")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "בוצע", taskAction(context, task.id, TaskReminders.ACTION_DONE, 1))
            .addAction(0, "נודניק · ${snoozeLabel(snooze)}", taskAction(context, task.id, TaskReminders.ACTION_SNOOZE, 2))
        post(context, taskNotificationId(task.id), b)
    }

    /** "עכשיו · 10:00", "להיום", "עבר המועד · 21.10". */
    private fun dueText(task: TaskEntity): String {
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
            Intent(context, TaskReminders.ActionReceiver::class.java)
                .setAction(action)
                .putExtra(TaskReminders.EXTRA_TASK_ID, taskId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** One notification per task (new or due — the later replaces the earlier); tap opens it. */
    private fun postTask(context: Context, task: TaskEntity, title: String, text: String, publicTitle: String) =
        post(context, taskNotificationId(task.id), taskBuilder(context, CHANNEL_TASKS, task, title, text, publicTitle))

    private fun taskBuilder(
        context: Context, channel: String, task: TaskEntity, title: String, text: String, publicTitle: String,
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
    const val EXTRA_MEMO_ID = "memo_id"
    const val EXTRA_OPEN_CALL = "open_call_id"

    private fun serviceAction(context: Context, action: String, requestCode: Int, callId: Long?, memoId: Long? = null): PendingIntent =
        PendingIntent.getService(
            context, requestCode,
            Intent(context, TranscriptionService::class.java)
                .setAction(action)
                .apply {
                    if (callId != null) putExtra(EXTRA_CALL_ID, callId)
                    if (memoId != null) putExtra(EXTRA_MEMO_ID, memoId)
                },
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
