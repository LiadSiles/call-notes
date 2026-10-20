package com.callnotes.capture

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import com.callnotes.CallNotesApp
import com.callnotes.data.CallEntity
import com.callnotes.data.CallStatus
import com.callnotes.data.SpeakerResult
import com.callnotes.util.Notifications
import com.callnotes.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Records every call from the microphone.
 *
 * It is an accessibility service because Android delivers microphone audio to an
 * ordinary app as silence while a call is active; accessibility services are the
 * documented exception. It subscribes to no accessibility events and cannot read
 * the screen.
 */
class CallRecorderService : AccessibilityService(), CallStateMonitor.Listener {

    private class Session(
        val startedAt: Long,
        val incoming: Boolean,
        val file: File,
        val recorder: WavRecorder?,
    ) {
        var speakerResult: String? = null
        var speakerMethod: String? = null
    }

    private lateinit var prefs: Prefs
    private lateinit var monitor: CallStateMonitor
    private lateinit var speaker: SpeakerController

    private val main = Handler(Looper.getMainLooper())
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var session: Session? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        speaker = SpeakerController(this)
        monitor = CallStateMonitor(this, this)
        instance = this
        ServiceStatus.update { it.copy(connected = true) }
        ensureMonitoring()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit // subscribes to none

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        session?.let { finish(it, System.currentTimeMillis()) }
        if (::monitor.isInitialized) monitor.stop()
        main.removeCallbacksAndMessages(null)
        ServiceStatus.update { ServiceStatus.State() }
        super.onDestroy()
        // Let the final database write finish before cancelling the scope.
        io.launch { delay(10_000); io.cancel() }
    }

    private fun ensureMonitoring() {
        if (ServiceStatus.state.value.monitoring) return
        if (monitor.start()) ServiceStatus.update { it.copy(monitoring = true) }
    }

    companion object {
        @Volatile private var instance: CallRecorderService? = null

        /**
         * The service receives no events, so a permission granted after it connected
         * is picked up when the app UI comes back to the foreground.
         */
        fun refreshFromUi() {
            instance?.let { svc -> svc.main.post { svc.ensureMonitoring() } }
        }

        private val SPEAKER_CHECKS_MS = listOf(1_000L, 2_200L, 3_800L, 6_000L)
    }

    // ---- Call lifecycle -----------------------------------------------------------

    override fun onCallStarted(incoming: Boolean) {
        if (!prefs.recordingEnabled || session != null) return

        val startedAt = System.currentTimeMillis()
        val dir = File(filesDir, "recordings").apply { mkdirs() }
        val file = File(dir, "call_$startedAt.wav")

        val recorder = if (hasMicPermission()) {
            WavRecorder(file, mainExecutor).takeIf { it.start() }
        } else null

        val current = Session(startedAt, incoming, file, recorder)
        session = current
        ServiceStatus.update { it.copy(recordingNow = recorder != null) }

        if (recorder == null) {
            Notifications.problem(this, "השיחה לא מוקלטת",
                "לא ניתן היה להתחיל הקלטה. פתח את האפליקציה כדי לבדוק הרשאות.")
        } else {
            Notifications.showRecording(this)
        }
        startSpeakerFlow(current)
    }

    override fun onCallEnded() {
        val current = session ?: return
        finish(current, System.currentTimeMillis())
    }

    private fun finish(current: Session, endedAt: Long) {
        session = null
        main.removeCallbacksAndMessages(null)
        current.recorder?.stop()
        speaker.restore()
        Notifications.cancelRecording(this)
        ServiceStatus.update { it.copy(recordingNow = false) }

        io.launch { save(current, endedAt) }
    }

    private suspend fun save(current: Session, endedAt: Long) {
        current.recorder?.awaitFinished()
        delay(2_000) // give the dialer time to write the call-log entry

        val log = CallLogResolver.resolve(this, current.startedAt, endedAt)
        val incoming = log?.incoming ?: current.incoming

        // An outgoing call nobody answered: we only recorded ringing — drop it.
        if (log != null && !incoming && log.durationSec == 0L) {
            current.file.delete()
            return
        }

        val recorder = current.recorder
        val entity = CallEntity(
            startedAt = current.startedAt,
            endedAt = endedAt,
            incoming = incoming,
            number = log?.number,
            contactName = log?.name,
            filePath = if (recorder != null) current.file.absolutePath else null,
            audioSource = recorder?.sourceName,
            peakLevel = recorder?.peakLevel ?: 0,
            silenced = recorder?.silenced ?: false,
            speakerResult = current.speakerResult ?: SpeakerResult.FAILED,
            speakerMethod = current.speakerMethod,
            status = if (recorder != null) CallStatus.RECORDED else CallStatus.RECORD_FAILED,
        )
        if (recorder == null) current.file.delete()
        (application as CallNotesApp).database.calls().insert(entity)
    }

    // ---- Speakerphone -----------------------------------------------------------

    private fun startSpeakerFlow(current: Session) {
        when {
            !prefs.autoSpeaker -> current.speakerResult = SpeakerResult.DISABLED
            speaker.headsetConnected() -> current.speakerResult = SpeakerResult.SKIPPED_HEADSET
            else -> {
                if (speaker.requestSpeaker()) current.speakerMethod = "api"
                // The in-call screen takes a moment to appear; check (and fall back) a few times.
                SPEAKER_CHECKS_MS.forEachIndexed { i, at ->
                    main.postDelayed({ checkSpeaker(current, last = i == SPEAKER_CHECKS_MS.lastIndex) }, at)
                }
            }
        }
    }

    private fun checkSpeaker(current: Session, last: Boolean) {
        if (session !== current || current.speakerResult != null) return

        if (speaker.isSpeakerOn()) {
            current.speakerResult = SpeakerResult.ON
            return
        }
        if (last) {
            current.speakerResult = SpeakerResult.FAILED
            current.speakerMethod = null
            Notifications.askForSpeaker(this)
            watchForUserSpeaker(current)
        }
        // Otherwise the route may still be settling; the next check looks again.
    }

    /** After asking the user, notice if they turn the speaker on themselves. */
    private fun watchForUserSpeaker(current: Session) {
        main.postDelayed({
            if (session !== current) return@postDelayed
            if (speaker.isSpeakerOn()) {
                current.speakerResult = SpeakerResult.ON
                current.speakerMethod = "user"
            } else {
                watchForUserSpeaker(current)
            }
        }, 3_000)
    }

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
}
