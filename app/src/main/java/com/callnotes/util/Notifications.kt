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

    const val ID_RECORDING = 1
    const val ID_PROCESSING = 4
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
    }

    /** The ongoing notification shown by [com.callnotes.capture.RecordingService]. */
    fun recording(context: Context): Notification =
        builder(context, CHANNEL_RECORDING, "מקליט את השיחה", "ההקלטה תופיע באפליקציה בסיום השיחה")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    /** Ongoing notification of [com.callnotes.processing.TranscriptionService]. */
    fun processing(context: Context, progress: String?): Notification =
        builder(context, CHANNEL_RECORDING, "מתמלל שיחות",
            if (progress == null) "טוען את המודל…" else "מקטע $progress")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(0, "עצירה", stopTranscription(context))
            .build()

    private fun stopTranscription(context: Context): PendingIntent = PendingIntent.getService(
        context, 1,
        Intent(context, com.callnotes.processing.TranscriptionService::class.java)
            .setAction(com.callnotes.processing.TranscriptionService.ACTION_STOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun updateProcessing(context: Context, progress: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        NotificationManagerCompat.from(context).notify(ID_PROCESSING, processing(context, progress))
    }

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
            .setContentIntent(openApp(context))

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
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
