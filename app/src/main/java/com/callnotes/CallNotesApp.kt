package com.callnotes

import android.app.Application
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
import com.callnotes.util.TaskReminders
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
        // The restart helper's own process must not touch the database or schedule anything.
        if (getProcessName().endsWith(":restart")) return
        DataProtection.clearCache(this)
        Notifications.createChannels(this)
        recoverDeadRun()
        TaskReminders.reschedule(this)
        Retention.scheduleDaily(this)
        appScope.launch {
            Retention.run(this@CallNotesApp)
            DataProtection.applyToAll(this@CallNotesApp)
        }
        val prefs = Prefs(this)
        // Only-while-charging: plugging in picks up the queue (unplugging lets the current call finish).
        Energy.watchCharger(this) { plugged ->
            if (plugged && prefs.autoTranscribe && prefs.onlyWhileCharging) TranscriptionService.start(this)
        }
        // Battery saver on → pause processing; off → pick up what's queued.
        BatterySaver.watch(this) { paused ->
            if (paused) TranscriptionService.pauseForBatterySaver(this)
            else if (prefs.autoTranscribe) TranscriptionService.start(this)
        }
    }

    /** Work that should outlive the screen that started it (e.g. converting recordings). */
    fun launchBackground(block: suspend () -> Unit) {
        appScope.launch { block() }
    }

    /**
     * If the last process died in the middle of transcription (out of memory, native
     * crash), say where it stopped and release the call it was on, so the user gets an
     * error and a retry button instead of an endless "transcribing…".
     */
    private fun recoverDeadRun() {
        val stage = ProcessingLog.recoverIfDied(this) ?: return
        TranscriptionService.note(this, "התמלול נעצר באמצע ($stage). ייתכן שחסר זיכרון למודל.")
        appScope.launch {
            database.calls().failInterrupted("התמלול נעצר באמצע: $stage. ייתכן שחסר זיכרון — נסה לסגור אפליקציות אחרות או לבחור את המודל הקטן.")
        }
    }
}
