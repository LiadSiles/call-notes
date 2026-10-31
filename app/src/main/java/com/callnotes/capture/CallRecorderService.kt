package com.callnotes.capture

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import com.callnotes.CallNotesApp
import com.callnotes.data.CallEntity
import com.callnotes.data.CallKind
import com.callnotes.data.CallStatus
import com.callnotes.data.SpeakerResult
import com.callnotes.processing.ProcessingLog
import com.callnotes.processing.TranscriptionService
import com.callnotes.util.BatterySaver
import com.callnotes.util.Notifications
import com.callnotes.util.Prefs
import com.callnotes.util.RecordingFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Records every call from the microphone — phone calls (telephony state) and,
 * if enabled, internet calls (audio mode, see [AudioModeMonitor]).
 *
 * It is an accessibility service because Android delivers microphone audio to an
 * ordinary app as silence while a call is active; accessibility services are the
 * documented exception. It subscribes to no accessibility events and cannot read
 * the screen.
 */
class CallRecorderService : AccessibilityService(), CallStateMonitor.Listener,
    AudioModeMonitor.Listener {

    private class Session(
        val startedAt: Long,
        val incoming: Boolean,
        val file: File,
        val recorder: WavRecorder?,
        val kind: String,
        val trace: CallTrace,
    ) {
        var speakerResult: String? = null
        var speakerMethod: String? = null
        /** The microphone foreground service reached the foreground during this call. */
        var foreground = false
    }

    private lateinit var prefs: Prefs
    private lateinit var monitor: CallStateMonitor
    private lateinit var modeMonitor: AudioModeMonitor
    private lateinit var speaker: SpeakerController

    private val main = Handler(Looper.getMainLooper())
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var session: Session? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        speaker = SpeakerController(this)
        monitor = CallStateMonitor(this, this)
        modeMonitor = AudioModeMonitor(this, this)
        modeMonitor.start() // no permission needed; the pref is checked per call
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
        if (::modeMonitor.isInitialized) modeMonitor.stop()
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

        /** Shorter internet "calls" are usually voice messages played via the earpiece. */
        private const val MIN_VOIP_MS = 8_000L
    }

    // ---- Call lifecycle -----------------------------------------------------------

    override fun onCallStarted(incoming: Boolean) {
        if (!prefs.recordingEnabled) return
        // A phone call takes over from an internet call in progress (rare).
        session?.takeIf { it.kind == CallKind.VOIP }?.let { finish(it, System.currentTimeMillis()) }
        if (session != null) return
        startSession(incoming, CallKind.CELLULAR)
    }

    override fun onCallEnded() {
        val current = session?.takeIf { it.kind == CallKind.CELLULAR } ?: return
        finish(current, System.currentTimeMillis())
    }

    override fun onVoipStarted() {
        if (!prefs.recordingEnabled || !prefs.recordVoip || session != null) return
        startSession(incoming = false, kind = CallKind.VOIP)
    }

    override fun onVoipEnded() {
        val current = session?.takeIf { it.kind == CallKind.VOIP } ?: return
        finish(current, System.currentTimeMillis())
    }

    private fun startSession(incoming: Boolean, kind: String) {
        if (BatterySaver.paused(this)) {
            ProcessingLog.write(this, "call not recorded: battery saver is on (paused by setting)")
            return
        }
        val startedAt = System.currentTimeMillis()
        val trace = CallTrace(startedAt)
        val audio = getSystemService(AudioManager::class.java)
        val power = getSystemService(PowerManager::class.java)
        trace.event("session start: kind=$kind mode=${modeName(audio.mode)} screen=${if (power.isInteractive) "on" else "off"}")
        // Foreground state first, so capture isn't silenced once the screen locks.
        val label = when (kind) {
            CallKind.VOIP -> "שיחת אינטרנט"
            else -> if (incoming) "שיחה נכנסת" else "שיחה יוצאת"
        } + " · " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(startedAt))
        trace.event("foreground service start requested: ${RecordingService.start(this, label)}")
        val dir = File(filesDir, "recordings").apply { mkdirs() }
        val file = File(dir, "call_$startedAt.wav")

        val recorder = if (hasMicPermission()) {
            WavRecorder(file, mainExecutor, trace).takeIf { it.start() }
        } else null

        val current = Session(startedAt, incoming, file, recorder, kind, trace)
        session = current
        watchDevice(current)
        ServiceStatus.update { it.copy(recordingNow = recorder != null) }

        if (recorder == null) {
            RecordingService.stop(this)
            Notifications.problem(this, "השיחה לא מוקלטת",
                "לא ניתן היה להתחיל הקלטה. פתח את האפליקציה כדי לבדוק הרשאות.")
        } else {
            // The service reports asynchronously; sample it a few times.
            listOf(500L, 2_000L, 5_000L).forEach { at ->
                main.postDelayed({
                    if (RecordingService.started) current.foreground = true
                    current.trace.event("foreground service running=${RecordingService.started} (check at ${at}ms)" +
                        (RecordingService.lastError?.let { " lastError=$it" } ?: ""))
                }, at)
            }
        }
        if (kind == CallKind.CELLULAR) {
            startSpeakerFlow(current)
        } else {
            current.speakerResult = SpeakerResult.NOT_APPLICABLE
        }
    }

    private fun finish(current: Session, endedAt: Long) {
        session = null
        current.trace.event("session end")
        unwatchDevice()
        main.removeCallbacksAndMessages(null)
        if (RecordingService.started) current.foreground = true
        current.recorder?.stop()
        // Start transcription while still in the foreground state (background FGS starts
        // can be refused); it waits a few seconds for this call to be saved.
        if (prefs.autoTranscribe && current.recorder != null && !prefs.tooLongForAuto(endedAt - current.startedAt)) {
            TranscriptionService.start(this, afterCall = true)
        }
        RecordingService.stop(this)
        speaker.restore()
        Notifications.cancelSpeakerPrompt(this)
        ServiceStatus.update { it.copy(recordingNow = false) }

        io.launch { save(current, endedAt) }
    }

    private suspend fun save(current: Session, endedAt: Long) {
        current.recorder?.awaitFinished()
        if (current.recorder != null) {
            try { current.trace.write(CallTrace.fileFor(current.file)) } catch (_: Exception) {}
        }

        val voip = current.kind == CallKind.VOIP
        if (voip && endedAt - current.startedAt < MIN_VOIP_MS) {
            RecordingFiles.delete(current.file.path)
            return
        }

        // The call log only covers phone calls; give the dialer time to write it.
        val log = if (voip) null else {
            delay(2_000)
            CallLogResolver.resolve(this, current.startedAt, endedAt)
        }
        val incoming = log?.incoming ?: current.incoming

        // An outgoing call nobody answered: we only recorded ringing — drop it.
        if (log != null && !incoming && log.durationSec == 0L) {
            RecordingFiles.delete(current.file.path)
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
            kind = current.kind,
            foreground = current.foreground,
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
            current.trace.event("speaker on (auto)")
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

    // ---- Diagnostics -----------------------------------------------------------------

    private var screenReceiver: BroadcastReceiver? = null
    private var routeListener: Any? = null

    /** Logs screen and audio-route changes into the session's trace. */
    private fun watchDevice(current: Session) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val what = when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> "screen OFF"
                    Intent.ACTION_SCREEN_ON -> "screen ON"
                    Intent.ACTION_USER_PRESENT -> "unlocked"
                    else -> intent.action
                }
                current.trace.event(what ?: "?")
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        screenReceiver = receiver

        if (Build.VERSION.SDK_INT >= 31) {
            val audio = getSystemService(AudioManager::class.java)
            val l = AudioManager.OnCommunicationDeviceChangedListener { device ->
                current.trace.event("call audio route type=${device?.type}")
            }
            audio.addOnCommunicationDeviceChangedListener(mainExecutor, l)
            routeListener = l
        }
    }

    private fun unwatchDevice() {
        screenReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        screenReceiver = null
        if (Build.VERSION.SDK_INT >= 31) {
            (routeListener as? AudioManager.OnCommunicationDeviceChangedListener)?.let {
                getSystemService(AudioManager::class.java).removeOnCommunicationDeviceChangedListener(it)
            }
        }
        routeListener = null
    }

    override fun onAudioMode(mode: Int) {
        session?.trace?.event("audio mode -> ${modeName(mode)}")
    }

    private fun modeName(mode: Int) = when (mode) {
        AudioManager.MODE_NORMAL -> "NORMAL"
        AudioManager.MODE_RINGTONE -> "RINGTONE"
        AudioManager.MODE_IN_CALL -> "IN_CALL"
        AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
        else -> "mode$mode"
    }

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
}
