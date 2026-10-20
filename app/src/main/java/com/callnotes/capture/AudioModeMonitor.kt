package com.callnotes.capture

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi

/**
 * Detects internet calls (WhatsApp, Telegram, Meet…) from the system audio mode.
 * VoIP apps switch the mode to MODE_IN_COMMUNICATION while a call is active; no
 * permission is needed to observe it. It can't tell which app or who is on the line.
 *
 * Some apps also enter this mode briefly (e.g. playing a voice message through the
 * earpiece), so the service discards very short sessions.
 */
class AudioModeMonitor(
    context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onVoipStarted()
        fun onVoipEnded()
    }

    private val audio = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val executor = context.mainExecutor
    private var inCommunication = false
    private var modernListener: Any? = null
    private var polling = false

    fun start() {
        if (modernListener != null || polling) return
        if (Build.VERSION.SDK_INT >= 31) {
            modernListener = registerModern()
        } else {
            polling = true
            poll()
        }
        handle(audio.mode)
    }

    fun stop() {
        if (Build.VERSION.SDK_INT >= 31) {
            (modernListener as? AudioManager.OnModeChangedListener)?.let {
                audio.removeOnModeChangedListener(it)
            }
        }
        modernListener = null
        polling = false
        main.removeCallbacksAndMessages(null)
    }

    @RequiresApi(31)
    private fun registerModern(): AudioManager.OnModeChangedListener {
        val l = AudioManager.OnModeChangedListener { mode -> handle(mode) }
        audio.addOnModeChangedListener(executor, l)
        return l
    }

    private fun poll() {
        if (!polling) return
        handle(audio.mode)
        main.postDelayed({ poll() }, 2_000)
    }

    private fun handle(mode: Int) {
        val now = mode == AudioManager.MODE_IN_COMMUNICATION
        if (now == inCommunication) return
        inCommunication = now
        if (now) listener.onVoipStarted() else listener.onVoipEnded()
    }
}
