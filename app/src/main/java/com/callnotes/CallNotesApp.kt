package com.callnotes

import android.app.Application
import com.callnotes.data.AnalysisStatus
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
import kotlinx.coroutines.runBlocking

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
        // The restart helper's own process must not touch the database or schedule anything.
        if (getProcessName().endsWith(":restart")) return
        // A crash takes the recording service down with the app (Android doesn't restart it):
        // write what crashed where, then let Android handle it as usual.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { ProcessingLog.crash(this, t.name, e) }
            previous?.uncaughtException(t, e)
        }
        ProcessingLog.logExits(this)
        Prefs(this).let { p ->
            ProcessingLog.write(this, "app started · ${p.switchesLine()}")
            p.logSwitchChanges { ProcessingLog.write(this, it) }
        }
        DataProtection.clearCache(this)
        // Before any processing can start in this process (two small folder listings).
        Storage.cleanLeftovers(this)
        Notifications.createChannels(this)
        recoverDeadRun()
        TaskReminders.reschedule(this)
        Retention.scheduleDaily(this)
        appScope.launch {
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
        val what = when {
            death.nativeCrash -> "העיבוד קרס"
            death.memory -> "הטלפון סגר את האפליקציה כי השתמשה ביותר מדי זיכרון"
            death.cleanup -> "האפליקציה נסגרה בניקוי אפליקציות"
            else -> "האפליקציה נסגרה"
        }
        // The process ended while Gemma was on the GPU, and not by a cleanup: Gemma uses the
        // CPU from now on, until the user turns the GPU on again in the summary settings. Any
        // reason counts — a native crash, the memory line, or the whole phone going down
        // (0.24.1 on the owner's phone: after a phone restart Android reports neither).
        if (death.onGpu && !death.cleanup && Prefs(this).summaryOnGpu) {
            Prefs(this).summaryOnGpu = false
            ProcessingLog.write(this, "the app stopped while Gemma was on the GPU — summaries use the CPU from now on")
        }
        // A call being transcribed when the process died is marked failed (one bad file must
        // not take the app down on every start); the user can retry it by hand. After a
        // cleanup it simply goes back to the queue. Before any worker starts (a starting
        // transcription worker fails whatever is still "transcribing").
        runBlocking(Dispatchers.IO) {
            if (death.cleanup) database.calls().requeueInterrupted()
            else database.calls().failInterrupted("$what באמצע התמלול. אפשר לנסות שוב ממסך השיחה.")
        }
        val call = death.summaryCall
        if (call == null) {
            TranscriptionService.note(this, if (death.cleanup) "$what באמצע התמלול. ההקלטה חזרה לממתינות לתמלול." else "$what באמצע התמלול.")
            return
        }
        // Recent apps cleared, or a cleaner app: not the summary's fault — it just runs again.
        if (death.cleanup) {
            TranscriptionService.note(this, "$what באמצע סיכום. הסיכום ימשיך.")
            return
        }
        // A summary is otherwise picked up again on every start: when it is what kills the
        // app, that is a loop, and each forced stop also turns accessibility off (0.22.2).
        // One more try, then it is marked failed and only runs again when the user asks.
        val prefs = Prefs(this)
        val deaths = prefs.summaryDeaths(call) + 1
        if (deaths < 2) {
            prefs.setSummaryDeaths(call, deaths)
            TranscriptionService.note(this, "$what באמצע סיכום. ינסה פעם אחת נוספת.")
            return
        }
        prefs.clearSummaryDeaths(call)
        ProcessingLog.write(this, "call $call: summary stopped ${deaths} times in a row with the app — marked failed, not retried automatically")
        TranscriptionService.note(this, "$what באמצע סיכום, פעמיים ברצף. הסיכום לא ינסה שוב לבד.")
        // Written before anything can start processing (the automatic summary would pick it up).
        runBlocking(Dispatchers.IO) {
            database.calls().setAnalysisStatus(call, AnalysisStatus.FAILED,
                "$what באמצע הסיכום, פעמיים ברצף, ולכן הסיכום לא ינסה שוב לבד. אפשר לנסות שוב ממסך השיחה.")
        }
    }
}
