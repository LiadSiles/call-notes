package com.callnotes.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat

/**
 * Turns telephony call-state changes into "call started" / "call ended" events.
 *
 * IDLE -> OFFHOOK is an outgoing call (recording starts at dialing),
 * IDLE -> RINGING -> OFFHOOK is an answered incoming call,
 * RINGING -> IDLE is a missed or rejected call and produces no events.
 */
class CallStateMonitor(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onCallStarted(incoming: Boolean)
        fun onCallEnded()
    }

    private val telephony = context.getSystemService(TelephonyManager::class.java)
    private var lastState = TelephonyManager.CALL_STATE_IDLE
    private var sawRinging = false
    private var registration: Any? = null

    /** Returns false if the phone-state permission is missing. */
    fun start(): Boolean {
        if (registration != null) return true
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) return false

        registration = if (Build.VERSION.SDK_INT >= 31) registerModern() else registerLegacy()
        return true
    }

    fun stop() {
        val reg = registration ?: return
        if (Build.VERSION.SDK_INT >= 31) {
            telephony.unregisterTelephonyCallback(reg as TelephonyCallback)
        } else {
            @Suppress("DEPRECATION")
            telephony.listen(reg as PhoneStateListener, PhoneStateListener.LISTEN_NONE)
        }
        registration = null
    }

    @RequiresApi(31)
    private fun registerModern(): TelephonyCallback {
        val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) = handle(state)
        }
        telephony.registerTelephonyCallback(context.mainExecutor, callback)
        return callback
    }

    @Suppress("DEPRECATION")
    private fun registerLegacy(): PhoneStateListener {
        val listener = object : PhoneStateListener() {
            @Deprecated("Deprecated in Java")
            override fun onCallStateChanged(state: Int, phoneNumber: String?) = handle(state)
        }
        telephony.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        return listener
    }

    private fun handle(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> sawRinging = true
            TelephonyManager.CALL_STATE_OFFHOOK ->
                if (lastState != TelephonyManager.CALL_STATE_OFFHOOK) listener.onCallStarted(sawRinging)
            TelephonyManager.CALL_STATE_IDLE -> {
                if (lastState == TelephonyManager.CALL_STATE_OFFHOOK) listener.onCallEnded()
                sawRinging = false
            }
        }
        lastState = state
    }
}
