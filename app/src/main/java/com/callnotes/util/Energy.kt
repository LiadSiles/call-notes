package com.callnotes.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat

/**
 * Battery readings around each operation, so the app can say what a transcription or a
 * summary cost. Uses the fuel gauge's charge counter (µAh) where the phone reports it,
 * otherwise whole battery percent. It measures the whole phone during the operation (the
 * screen, other apps, a parallel summary), so it's an upper bound, not an exact figure,
 * and nothing is measured while the phone is plugged in.
 */
object Energy {

    data class Reading(val chargeUah: Long?, val level: Int, val plugged: Boolean)

    fun read(context: Context): Reading {
        val bm = context.getSystemService(BatteryManager::class.java)
        val charge = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER).takeIf { it > 0 && it != Long.MIN_VALUE }
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return Reading(charge, level, pluggedIn(context))
    }

    /** Battery percent used between [before] and [after], or null if unknown (plugged in, no data). */
    fun percentUsed(before: Reading, after: Reading): Double? {
        if (before.plugged || after.plugged) return null
        val c0 = before.chargeUah
        val c1 = after.chargeUah
        if (c0 != null && c1 != null && before.level in 1..100) {
            val full = c0 * 100.0 / before.level // estimated full charge, µAh
            return ((c0 - c1) / full * 100).coerceAtLeast(0.0)
        }
        return (before.level - after.level).toDouble().coerceAtLeast(0.0)
    }

    /** "~3.2% סוללה" or null. */
    fun label(percent: Double?): String? = percent?.let { "~%.1f%% סוללה".format(it) }

    /** The phone is connected to a charger (USB, AC, wireless, dock), full or not. */
    fun pluggedIn(context: Context): Boolean {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    }

    /** Automatic transcription is set to wait for the charger, and the phone isn't plugged in. */
    fun transcribeWaitsForCharger(context: Context): Boolean =
        Prefs(context).transcribeOnlyCharging && !pluggedIn(context)

    /** Automatic summaries are set to wait for the charger, and the phone isn't plugged in. */
    fun summaryWaitsForCharger(context: Context): Boolean =
        Prefs(context).summarizeOnlyCharging && !pluggedIn(context)

    /**
     * Automatic work that may run now: each step is on, and not waiting for the charger.
     * Manual requests don't look at this.
     */
    fun autoTranscribeAllowed(context: Context) = Prefs(context).autoTranscribe && !transcribeWaitsForCharger(context)
    fun autoSummaryAllowed(context: Context) = Prefs(context).autoSummarize && !summaryWaitsForCharger(context)

    /** Charger connected / disconnected, for the life of the process. */
    fun watchCharger(context: Context, onChange: (plugged: Boolean) -> Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) = onChange(intent.action == Intent.ACTION_POWER_CONNECTED)
        }
        ContextCompat.registerReceiver(
            context, receiver,
            IntentFilter().apply { addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED) },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }
}
