package com.callnotes.processing

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.callnotes.CallNotesApp
import com.callnotes.data.AnalysisStatus
import com.callnotes.inference.ModelRole
import com.callnotes.util.Notifications
import com.callnotes.util.Prefs
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Background processing: two workers at the same time — [TranscribeWorker] (Whisper +
 * speakers) and [SummaryWorker] (Gemma). Each shows its own notification naming the call
 * it is working on, with a cancel button, and removes it when done; the service stays in
 * the foreground with whichever is running and stops when both are idle.
 *
 * Cancel is immediate from the user's point of view: the UI state, the database and the
 * notification change at once, and the native engine is told to abort (whisper.cpp abort
 * callback, LiteRT-LM cancelProcess), so it stops within moments.
 */
class TranscriptionService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private val crashHandler = CoroutineExceptionHandler { _, e ->
        ProcessingLog.end(this, "worker crashed: ${describe(e)}")
        note("העיבוד נעצר בשגיאה: ${e.javaClass.simpleName}")
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + crashHandler)
    val transcribe = TranscribeWorker(this)
    val summary = SummaryWorker(this)

    private var wakeLock: PowerManager.WakeLock? = null
    private var lastStartId = 0
    private var stoppingAll = false
    /** Main thread: the notification id the service is in the foreground with. */
    private var anchor: Int? = null
    /** Main thread: the latest notification of each running worker. */
    private val shown = HashMap<Int, Notification>()

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_STOP -> { stopAll(this); finishIfIdle(); return START_NOT_STICKY }
            Notifications.ACTION_CANCEL -> {
                intent.getLongExtra(Notifications.EXTRA_CALL_ID, -1L).takeIf { it > 0 }?.let { cancelCall(this, it) }
                finishIfIdle()
                return START_NOT_STICKY
            }
        }
        if (anchor == null) {
            try {
                goForeground(Notifications.ID_TRANSCRIBE, Notifications.work(this, Notifications.ID_TRANSCRIBE, "מכין…", ""))
                anchor = Notifications.ID_TRANSCRIBE
            } catch (e: Exception) {
                ProcessingLog.write(this, "startForeground refused: ${describe(e)}")
                note("לא ניתן היה להפעיל את העיבוד ברקע: ${e.javaClass.simpleName}")
                ProcessingStatus.clearRequests()
                finishIfIdle()
                return START_NOT_STICKY
            }
        }
        stoppingAll = false
        intent?.getLongExtra(EXTRA_CALL_ID, -1L)?.takeIf { it > 0 }?.let { transcribe.onRequest(it) }
        if (intent?.getBooleanExtra(EXTRA_AFTER_CALL, false) == true && !transcribe.running) transcribe.delayFirst = true
        ensure(transcribe)
        ensure(summary)
        return START_NOT_STICKY
    }

    /** Main thread: runs [w] if it isn't running; a running summary worker is just woken. */
    private fun ensure(w: Worker) {
        if (w.running) {
            if (w === summary) summary.wake.trySend(Unit)
            return
        }
        if (wakeLock?.isHeld != true) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "callnotes:processing")
                .apply { acquire(3 * 60 * 60 * 1000L) }
        }
        lateinit var job: Job
        job = scope.launch {
            try { w.loop() } finally { main.post { onWorkerDone(w, job) } }
        }
        w.job = job
    }

    /** Any thread: a transcript is ready, so summaries may have work. */
    fun wakeSummary() = main.post { if (!stoppingAll) ensure(summary) }

    private fun onWorkerDone(w: Worker, job: Job) {
        if (w.job === job) w.job = null
        hideWorkNow(w)
        // A request may have slipped in while the worker was finishing.
        if (!stoppingAll && w === transcribe && ProcessingStatus.firstRequested() != null) { ensure(transcribe); return }
        if (!stoppingAll && w === summary && ProcessingStatus.firstAnalysisRequested() != null) { ensure(summary); return }
        finishIfIdle()
    }

    private fun finishIfIdle() {
        if (transcribe.running || summary.running) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        anchor = null
        shown.clear()
        nm().cancel(Notifications.ID_TRANSCRIBE)
        nm().cancel(Notifications.ID_SUMMARY)
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        stoppingAll = false
        stopSelf(lastStartId)
    }

    /** Any thread: show or update [w]'s notification. */
    fun showWork(w: Worker, title: String, text: String, done: Int = 0, total: Int = 0, callId: Long? = null) {
        val n = Notifications.work(this, w.notificationId, title, text, done, total, callId)
        main.post {
            if (!w.running && w.job == null) return@post
            shown[w.notificationId] = n
            if (anchor == null) {
                runCatching { goForeground(w.notificationId, n); anchor = w.notificationId }
            } else {
                notify(w.notificationId, n)
            }
        }
    }

    /** Any thread: remove [w]'s notification (e.g. the summary worker waiting for work). */
    fun hideWork(w: Worker) = main.post { hideWorkNow(w) }

    private fun hideWorkNow(w: Worker) {
        val id = w.notificationId
        shown.remove(id)
        if (anchor != id) { nm().cancel(id); return }
        // The foreground notification must stay while anything runs: move it to the other worker.
        val other = if (w === transcribe) summary else transcribe
        if (other.running) {
            val n = shown[other.notificationId]
                ?: Notifications.work(this, other.notificationId, "מעבד…", "")
            runCatching { goForeground(other.notificationId, n); anchor = other.notificationId }
            nm().cancel(id)
        }
    }

    private fun nm() = getSystemService(NotificationManager::class.java)

    private fun notify(id: Int, n: Notification) {
        runCatching { nm().notify(id, n) } // silently not shown without the notification permission
    }

    /**
     * Enters the foreground with an explicit type (required from API 34). Calls the
     * platform method directly: `ServiceCompat.startForeground` masks the type with the
     * set it knows (API 34), which drops `mediaProcessing` (API 35) to "none" — Android
     * then refuses with InvalidForegroundServiceTypeException (0.3.5 bug). Falls back to
     * dataSync if the media-processing type is refused.
     */
    private fun goForeground(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= 35) {
            try {
                startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
                return
            } catch (e: Exception) {
                ProcessingLog.write(this, "mediaProcessing refused, trying dataSync: ${describe(e)}")
            }
        }
        startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    /** Main thread. */
    private fun stopAllNow() {
        stoppingAll = true
        ProcessingLog.write(this, "stop requested by the user")
        transcribe.stopNow()
        summary.stopNow()
        note("העיבוד נעצר. התמלול והסיכום האוטומטיים כבויים עד שתפעיל אותם שוב.")
    }

    /** Main thread: like stop-all, but automatic processing stays on (resumes later). */
    private fun pauseNow() {
        stoppingAll = true
        ProcessingLog.write(this, "paused: battery saver on")
        transcribe.stopNow()
        summary.stopNow()
    }

    private fun note(text: String) = note(this, text)

    override fun onDestroy() {
        if (instance === this) instance = null
        scope.cancel()
        ProcessingStatus.set(ProcessingStatus.State())
        ProcessingStatus.setSummary(ProcessingStatus.State())
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_AFTER_CALL = "after_call"
        private const val EXTRA_CALL_ID = "call_id"
        const val ACTION_STOP = "com.callnotes.action.STOP_TRANSCRIPTION"

        @Volatile private var instance: TranscriptionService? = null

        /**
         * Cancels one call everywhere, at once: out of both queues, its progress gone from
         * the UI, the database updated (held / summary skipped; an older transcript comes
         * back), and the engine working on it aborted.
         */
        fun cancelCall(context: Context, id: Long) {
            ProcessingStatus.unrequest(id)
            ProcessingStatus.unrequestAnalysis(id)
            if (ProcessingStatus.state.value.callId == id) ProcessingStatus.set(ProcessingStatus.State(running = true))
            val summarising = ProcessingStatus.summary.value.callId == id
            if (summarising) ProcessingStatus.setSummary(ProcessingStatus.State(running = true))
            val app = context.applicationContext as CallNotesApp
            val dao = app.database.calls()
            app.launchBackground {
                dao.cancelWaiting(id)
                dao.cancelRunningTranscription(id)
                dao.cancelAnalysisWaiting(id)
                if (summarising) dao.setAnalysisStatus(id, AnalysisStatus.SKIPPED, null)
            }
            Handler(Looper.getMainLooper()).post {
                instance?.transcribe?.cancelCall(id)
                instance?.summary?.cancelCall(id)
            }
        }

        /**
         * The charger was unplugged (and stayed unplugged — see CallNotesApp's debounce):
         * a worker set to "only while charging" stops at once unless it's on a call the
         * user asked for. Its call goes back to the queue (an earlier transcript stays) and
         * restarts from the beginning when the phone is plugged in again; a model copy in
         * preparation is discarded. Requested calls still queued run after it stops.
         */
        fun pauseForCharger(context: Context) {
            val prefs = Prefs(context)
            Handler(Looper.getMainLooper()).post {
                val svc = instance ?: return@post
                var paused = false
                if (prefs.transcribeOnlyCharging && svc.transcribe.running && !svc.transcribe.currentIsManual) {
                    ProcessingLog.write(context, "charger unplugged: automatic transcription paused")
                    svc.transcribe.stopNow()
                    paused = true
                }
                if (prefs.summarizeOnlyCharging && svc.summary.running && !svc.summary.currentIsManual) {
                    ProcessingLog.write(context, "charger unplugged: automatic summaries paused")
                    svc.summary.stopNow()
                    paused = true
                }
                if (paused) note(context, "המטען נותק — העיבוד האוטומטי ימשיך כשהטלפון יחובר שוב")
            }
        }

        /** Battery saver turned on (and the user chose to pause): stop now, keep the queue. */
        fun pauseForBatterySaver(context: Context) {
            ProcessingStatus.set(ProcessingStatus.State())
            ProcessingStatus.setSummary(ProcessingStatus.State())
            note(context, "הושהה — מצב חיסכון בסוללה פעיל. ימשיך כשיכבה.")
            Handler(Looper.getMainLooper()).post { instance?.pauseNow() }
        }

        /**
         * Stops all processing now and turns automatic transcription and summaries off, so
         * nothing starts again on the next app open. Calls in progress go back to the queue.
         */
        fun stopAll(context: Context) {
            Prefs(context).apply { autoTranscribe = false; autoSummarize = false }
            ProcessingStatus.clearRequests()
            ProcessingStatus.set(ProcessingStatus.State())
            ProcessingStatus.setSummary(ProcessingStatus.State())
            ProcessingStatus.touch()
            Handler(Looper.getMainLooper()).post { instance?.stopAllNow() }
        }

        /**
         * Starts processing if a model is ready. Safe to call often. With [callId] that
         * call is transcribed next, ahead of everything else; with [analyseId] that call is
         * summarised next. Returns false (and records why in the "last run" note) if it
         * couldn't start.
         */
        fun start(context: Context, afterCall: Boolean = false, callId: Long? = null, analyseId: Long? = null): Boolean {
            val app = context.applicationContext as CallNotesApp
            if (com.callnotes.util.BatterySaver.paused(context)) {
                note(context, "מושהה — מצב חיסכון בסוללה פעיל")
                return false
            }
            // Automatic start: only if some automatic step may run now (switched on, and
            // not waiting for the charger). Explicit requests always run.
            if (callId == null && analyseId == null &&
                !com.callnotes.util.Energy.autoTranscribeAllowed(context) && !com.callnotes.util.Energy.autoSummaryAllowed(context)
            ) {
                val prefs = Prefs(context)
                if (prefs.anyAuto) note(context, "ממתין לטעינה — העיבוד יתחיל כשהטלפון יחובר למטען")
                return false
            }
            val canTranscribe = app.models.readyModel() != null
            val canAnalyse = app.models.readyModel(ModelRole.ANALYZE) != null
            when {
                analyseId != null && !canAnalyse -> {
                    note(context, "אין מודל סיכום מותקן — הורד את Gemma בהגדרות")
                    return false
                }
                callId != null && !canTranscribe || !canTranscribe && !canAnalyse -> {
                    if (callId != null) ProcessingStatus.unrequest(callId)
                    note(context, "אין מודל תמלול מותקן — התמלול לא הופעל")
                    return false
                }
            }
            if (analyseId != null) {
                ProcessingStatus.requestAnalysis(analyseId)
                instance?.summary?.cancelled?.remove(analyseId)
            }
            if (callId != null) {
                ProcessingStatus.request(callId)
                instance?.transcribe?.cancelled?.remove(callId)
            }
            return try {
                val intent = Intent(context, TranscriptionService::class.java).putExtra(EXTRA_AFTER_CALL, afterCall)
                if (callId != null) intent.putExtra(EXTRA_CALL_ID, callId)
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (e: Exception) {
                // e.g. background start not allowed; picked up when the app opens.
                if (callId != null) ProcessingStatus.unrequest(callId)
                ProcessingLog.write(context, "service start refused: ${describe(e)}")
                note(context, "העיבוד לא הופעל (${e.javaClass.simpleName}). ינסה שוב כשהאפליקציה תיפתח.")
                false
            }
        }

        /** Records the outcome of the last run where the settings screen can show it. */
        fun note(context: Context, text: String) {
            val time = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.US).format(java.util.Date())
            Prefs(context).lastProcessing = "$time · $text"
            ProcessingStatus.touch()
        }

        /** Short SHA-256 of a file (first 12 hex chars), for the log. */
        internal fun fingerprint(file: File): String =
            runCatching { com.callnotes.util.Hashing.sha256Hex(file).take(12) }.getOrDefault("unreadable")

        /** Exception with its first app/library frames, for the log. */
        internal fun describe(e: Throwable): String {
            val frames = e.stackTrace.take(4).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
            val cause = e.cause?.let { " | cause ${it.javaClass.simpleName}: ${it.message}" } ?: ""
            return "${e.javaClass.name}: ${e.message}$cause @ $frames"
        }
    }
}
