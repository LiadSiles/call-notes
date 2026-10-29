package com.callnotes

import android.app.Application
import com.callnotes.capture.PendingCalls
import com.callnotes.capture.ServiceStatus
import com.callnotes.data.AppDatabase
import com.callnotes.data.CallListItem
import com.callnotes.inference.ModelManager
import com.callnotes.processing.ProcessingLog
import com.callnotes.processing.TranscriptionService
import com.callnotes.security.DataProtection
import com.callnotes.util.BatterySaver
import com.callnotes.util.Energy
import com.callnotes.util.Notifications
import com.callnotes.util.Prefs
import com.callnotes.util.Retention
import com.callnotes.util.Storage
import com.callnotes.util.TaskReminders
import com.callnotes.util.VoiceMemos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Process-wide objects (database, model manager, the warm call list) and start-up work:
 * notification channels, recovery of a run that died, reminders and retention, and the
 * battery-saver / charger watchers that pause or resume processing.
 */
class CallNotesApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: AppDatabase by lazy { AppDatabase.build(this) }
    val models: ModelManager by lazy { ModelManager(this) }

    /**
     * The call list, kept warm for the whole process so screens show it instantly when
     * they reappear (re-subscribing per screen started from an empty list and flashed
     * the empty state). Null until the first database read completes.
     */
    val callList: StateFlow<List<CallListItem>?> by lazy {
        database.calls().observeList().stateIn(appScope, SharingStarted.Eagerly, null)
    }

    override fun onCreate() {
        super.onCreate()
        val process = getProcessName()
        // The restart helper's own process must not touch the database or schedule anything.
        if (process.endsWith(":restart")) return
        // Write what crashed where, then let Android handle it as usual.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { ProcessingLog.crash(this, "${process.substringAfter(':', "main")}/${t.name}", e) }
            previous?.uncaughtException(t, e)
        }
        // The recording service's own process (see capture/PendingCalls): it only records.
        // No database, models, processing or schedules here — those belong to the main process.
        if (process.endsWith(RECORDER_SUFFIX)) {
            inRecorderProcess = true
            Notifications.createChannels(this)
            // This process lives as long as the service; the main one may not be running.
            // So plugging in (steps set to "only while charging") and battery saver ending
            // wake it from here. (Unplugging and saver turning on pause it — only matters
            // while it runs, and it watches those itself.)
            Energy.watchCharger(this) { plugged ->
                val p = Prefs(this)
                if (plugged && (p.autoTranscribe && p.transcribeOnlyCharging || p.autoSummarize && p.summarizeOnlyCharging)) {
                    TranscriptionService.wakeFromRecorder(this, afterCall = false)
                }
            }
            BatterySaver.watch(this) { paused ->
                val p = Prefs(this)
                if (!paused && p.pauseInBatterySaver && p.anyAuto) TranscriptionService.wakeFromRecorder(this, afterCall = false)
            }
            return
        }
        ProcessingLog.logExits(this)
        ServiceStatus.listen(this)
        DataProtection.clearCache(this)
        // Before any processing can start in this process (two small folder listings).
        Storage.cleanLeftovers(this)
        Notifications.createChannels(this)
        recoverDeadRun()
        TaskReminders.reschedule(this)
        Retention.scheduleDaily(this)
        appScope.launch {
            // Calls the recording process finished while this process wasn't running.
            if (PendingCalls.importAll(this@CallNotesApp) > 0 && Prefs(this@CallNotesApp).anyAuto) {
                TranscriptionService.start(this@CallNotesApp)
            }
            Retention.run(this@CallNotesApp)
            DataProtection.applyToAll(this@CallNotesApp)
            VoiceMemos.cleanUp(this@CallNotesApp)
        }
        val prefs = Prefs(this)
        watchCharger(prefs)
        // Battery saver on → pause processing; off → pick up what's queued.
        BatterySaver.watch(this) { paused ->
            if (paused) TranscriptionService.pauseForBatterySaver(this)
            else {
                if (prefs.anyAuto) TranscriptionService.start(this)
                appScope.launch { VoiceMemos.resume(this@CallNotesApp) }
            }
        }
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val unplugged = Runnable {
        // Still unplugged after the grace period: pause what is set to "only while charging".
        if (!Energy.pluggedIn(this)) TranscriptionService.pauseForCharger(this)
    }

    /**
     * "Only while charging" (per step): plugging in starts the queue; unplugging pauses it
     * after [UNPLUG_GRACE_MS], so a loose cable, a wireless pad nudged out of place or a
     * quick unplug-replug doesn't throw away work in progress. Also checked once at
     * start-up (the phone may have booted on the charger).
     */
    private fun watchCharger(prefs: Prefs) {
        fun anyWaitsForCharger() = prefs.autoTranscribe && prefs.transcribeOnlyCharging ||
            prefs.autoSummarize && prefs.summarizeOnlyCharging
        Energy.watchCharger(this) { plugged ->
            main.removeCallbacks(unplugged)
            if (plugged) {
                if (anyWaitsForCharger()) TranscriptionService.start(this)
            } else {
                main.postDelayed(unplugged, UNPLUG_GRACE_MS)
            }
        }
        if (anyWaitsForCharger() && Energy.pluggedIn(this)) TranscriptionService.start(this)
    }

    /** Work that should outlive the screen that started it (e.g. converting recordings). */
    fun launchBackground(block: suspend () -> Unit) {
        appScope.launch { block() }
    }

    companion object {
        /** The app is on screen (set by MainActivity): a finished voice task needs no notification then. */
        @Volatile var onScreen = false

        /** Name suffix of the recording service's process (manifest `android:process`). */
        const val RECORDER_SUFFIX = ":recorder"

        /** This is the recording process: settings are re-read from disk (the main process writes them). */
        @Volatile var inRecorderProcess = false
            private set

        /** How long the charger must stay unplugged before automatic work pauses. */
        private const val UNPLUG_GRACE_MS = 15_000L
    }

    /**
     * If the last process died in the middle of transcription (out of memory, native
     * crash), say where it stopped and release the call it was on, so the user gets an
     * error and a retry button instead of an endless "transcribing…".
     */
    private fun recoverDeadRun() {
        val death = ProcessingLog.recoverIfDied(this) ?: return
        val stage = death.stage
        val (note, error) = if (death.nativeCrash) {
            "העיבוד קרס באמצע ($stage)." to
                "העיבוד קרס באמצע: $stage. אפשר לנסות שוב; אם זה חוזר, שלח את יומן התמלול (כפתור \"יומן\")."
        } else {
            "התמלול נעצר באמצע ($stage). ייתכן שחסר זיכרון למודל." to
                "התמלול נעצר באמצע: $stage. ייתכן שחסר זיכרון — נסה לסגור אפליקציות אחרות או לבחור את המודל הקטן."
        }
        TranscriptionService.note(this, note)
        appScope.launch { database.calls().failInterrupted(error) }
    }
}
