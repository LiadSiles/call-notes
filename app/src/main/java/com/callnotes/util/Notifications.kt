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

object Notifications {

    private const val CHANNEL_RECORDING = "recording"
    private const val CHANNEL_ALERTS = "alerts"
    private const val CHANNEL_TASKS = "tasks"

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
        val who = call.contactName ?: call.number ?: when (call.kind) {
            com.callnotes.data.CallKind.VOIP -> "שיחת אינטרנט"
            com.callnotes.data.CallKind.IMPORT -> "קובץ מיובא"
            else -> if (call.incoming) "שיחה נכנסת" else "שיחה יוצאת"
        }
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
        val text = listOfNotNull(callLabel, due?.let { "עד $it" }, if (!task.certain) "לא בטוח" else null).joinToString(" · ")
        val open = openTask(context, task.id)
        post(context, taskNotificationId(task.id),
            NotificationCompat.Builder(context, CHANNEL_TASKS)
                .also { hideOnLockScreen(context, it, CHANNEL_TASKS, "משימה חדשה") }
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("משימה חדשה: ${task.title}")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(task.title + "\n" + text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setGroup("tasks")
                .setCategory(NotificationCompat.CATEGORY_REMINDER))
    }

    /** A task is due today (or was due while the phone was off). Tap opens it. */
    fun taskDue(context: Context, task: com.callnotes.data.TaskEntity, callLabel: String?) {
        val late = task.dueDate?.let { runCatching { java.time.LocalDate.parse(it).isBefore(java.time.LocalDate.now()) }.getOrNull() } == true
        val text = listOfNotNull(if (late) "עבר המועד" else "להיום", callLabel).joinToString(" · ")
        post(context, taskNotificationId(task.id),
            NotificationCompat.Builder(context, CHANNEL_TASKS)
                .also { hideOnLockScreen(context, it, CHANNEL_TASKS, "תזכורת למשימה") }
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("תזכורת: ${task.title}")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(task.title + "\n" + text))
                .setContentIntent(openTask(context, task.id))
                .setAutoCancel(true)
                .setGroup("tasks")
                .setCategory(NotificationCompat.CATEGORY_REMINDER))
    }

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
