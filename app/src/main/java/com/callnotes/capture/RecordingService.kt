package com.callnotes.capture

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.callnotes.util.Notifications

/**
 * Foreground service of type "microphone" that runs for the length of a recording.
 *
 * The recorder itself lives in [CallRecorderService]; this service only keeps the app's
 * process in the foreground state Android requires for microphone access, so capture
 * isn't silenced when the screen locks. It always shows the ongoing "recording"
 * notification, and the system shows its own microphone-in-use indicator.
 */
class RecordingService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        started = try {
            ServiceCompat.startForeground(this, Notifications.ID_RECORDING, Notifications.recording(this), type)
            true
        } catch (e: Exception) {
            // e.g. ForegroundServiceStartNotAllowedException / SecurityException on some devices.
            lastError = "${e.javaClass.simpleName}: ${e.message}"
            stopSelf()
            false
        }
        if (started) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "callnotes:recording")
                .apply { acquire(4 * 60 * 60 * 1000L) } // safety cap: 4 h
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        started = false
        super.onDestroy()
    }

    companion object {
        /** True while the service is in the foreground. Read for per-call diagnostics. */
        @Volatile var started: Boolean = false
            private set

        /** Why the last start failed, for the call trace. */
        @Volatile var lastError: String? = null
            private set

        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java))
            true
        } catch (e: Exception) {
            false
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RecordingService::class.java))
        }
    }
}
