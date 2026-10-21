package com.callnotes.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat

/**
 * Optional "do nothing while battery saver is on" (owner's request): no new recordings and
 * no transcription or summaries. A recording already in progress finishes, so a call isn't
 * cut in half; queued work resumes when battery saver turns off.
 */
object BatterySaver {

    fun on(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isPowerSaveMode

    /** True when the user chose to pause in battery saver and it is on now. */
    fun paused(context: Context): Boolean = Prefs(context).pauseInBatterySaver && on(context)

    /** Watches battery saver for the life of the process (the accessibility service keeps it alive). */
    fun watch(context: Context, onChange: (pausedNow: Boolean) -> Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) = onChange(paused(c))
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }
}
