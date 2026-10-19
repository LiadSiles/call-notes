package com.callnotes.util

import android.Manifest
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

    private const val ID_RECORDING = 1
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

    fun showRecording(context: Context) = post(
        context, ID_RECORDING,
        builder(context, CHANNEL_RECORDING, "מקליט את השיחה", "הסיכום יופיע באפליקציה בסיום השיחה")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
    )

    fun cancelRecording(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID_RECORDING)
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
