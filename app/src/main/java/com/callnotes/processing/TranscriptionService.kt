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
import com.callnotes.data.MemoState
import com.callnotes.inference.InferenceThreads
import com.callnotes.inference.ModelRole
import com.callnotes.util.BatterySaver
import com.callnotes.util.Energy
import com.callnotes.util.Hashing
import com.callnotes.util.Notifications
import com.callnotes.util.Prefs
import com.callnotes.util.VoiceMemos
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Background processing: two workers at the same time — [TranscribeWorker] (Whisper +
 * speakers) and [SummaryWorker] (Gemma). Voice notes for new tasks pass through both
 * (Whisper, then Gemma). What the user asks for waits its turn in [ProcessingStatus.queue]
 * (first asked, first served, before automatic work); nothing in progress is interrupted.
 *
 * Notifications: one row per thing in progress and one per request waiting (with its place
 * in the queue); a waiting row turns into the progress row when its turn comes. Each has a
 * cancel button. The service stays in the foreground with one of the rows (moved when that
 * one goes) and stops when both workers are idle.
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
    /** Main thread: every processing row in the notification shade, by notification id. */
    private val rows = LinkedHashMap<Int, Notification>()
    /** Main thread: the row each worker shows for what it is doing now. */
    private val workerRows = HashMap<Worker, Int>()
    /** Main thread: rows of requests waiting their turn. */
    private val waitingRows = HashSet<Int>()
    /** "דני · 21.10 14:05" per call, for the waiting rows (read once). */
    private val labels = ConcurrentHashMap<Long, String>()

    override fun onCreate() {
        super.onCreate()
        instance = this
        scope.launch { ProcessingStatus.queue.collect { main.post { refreshWaiting() } } }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_STOP -> { stopAll(this); finishIfIdle(); return START_NOT_STICKY }
            Notifications.ACTION_CANCEL -> {
                intent.getLongExtra(Notifications.EXTRA_CALL_ID, -1L).takeIf { it > 0 }?.let { cancelCall(this, it) }
                intent.getLongExtra(Notifications.EXTRA_MEMO_ID, -1L).takeIf { it > 0 }?.let { id ->
                    // Same as "ביטול" on the note in the tasks screen: the recording is thrown away.
                    ProcessingStatus.removeMemo(id)
                    val app = application as CallNotesApp
                    app.launchBackground { app.database.calls().getMemo(id)?.let { VoiceMemos.delete(app, it) } }
                }
                finishIfIdle()
                return START_NOT_STICKY
            }
        }
        if (anchor == null) {
            try {
                goForeground(Notifications.ID_SERVICE, Notifications.service(this))
                anchor = Notifications.ID_SERVICE
            } catch (e: Exception) {
                ProcessingLog.write(this, "startForeground refused: ${describe(e)}")
                note("לא ניתן היה להפעיל את העיבוד ברקע: ${e.javaClass.simpleName}")
                ProcessingStatus.clearQueue()
                finishIfIdle()
                return START_NOT_STICKY
            }
        }
        stoppingAll = false
        refreshWaiting()
        // A voice note was recorded: it joins the queue now, so its row shows while others run.
        if (intent?.getBooleanExtra(EXTRA_MEMO, false) == true) scope.launch { syncMemos() }
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
        w.requestsSeen = ProcessingStatus.enqueued
        lateinit var job: Job
        job = scope.launch(InferenceThreads.dispatcher) {
            try { w.loop() } finally { main.post { onWorkerDone(w, job) } }
        }
        w.job = job
    }

    /** Any thread: a transcript is ready, so summaries may have work. */
    fun wakeSummary() = main.post { if (!stoppingAll) ensure(summary) }

    private fun onWorkerDone(w: Worker, job: Job) {
        if (w.job === job) w.job = null
        // A request or a finished transcript may have slipped in while the worker was
        // finishing. (Only a request added during the run counts: one the worker couldn't
        // take — battery saver, no model — must not restart it in a loop.)
        val newRequest = ProcessingStatus.first(w.stage) != null && ProcessingStatus.enqueued != w.requestsSeen
        val newTranscript = w === summary && summary.wake.tryReceive().isSuccess
        if (!stoppingAll && (newRequest || newTranscript)) { ensure(w); return }
        workerRows.remove(w)?.let { if (transcribe.running || summary.running) removeRow(it) }
        finishIfIdle()
    }

    private fun finishIfIdle() {
        if (transcribe.running || summary.running) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        anchor = null
        (rows.keys + Notifications.ID_SERVICE).forEach { nm().cancel(it) }
        rows.clear(); workerRows.clear(); waitingRows.clear()
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        stoppingAll = false
        stopSelf(lastStartId)
    }

    /**
     * Any thread: show or update the row of what [w] is working on now (its current voice
     * note or call). The row of what it worked on before goes; a waiting row of the same
     * request becomes this one.
     */
    fun showWork(w: Worker, title: String, text: String, done: Int = 0, total: Int = 0) {
        val memo = w.memoId
        val call = if (memo == null) w.callId else null
        if (memo == null && call == null) return
        val id = Notifications.rowId(w.stage, call ?: 0, memo ?: 0)
        val n = Notifications.work(this, id, w.stage, title, text, done, total, callId = call, memoId = memo)
        main.post {
            if (!w.running && w.job == null) return@post
            workerRows.put(w, id)?.takeIf { it != id }?.let(::removeRow)
            waitingRows.remove(id)
            putRow(id, n)
        }
    }

    /** Any thread: remove [w]'s row (e.g. the summary worker waiting for a transcript). */
    fun hideWork(w: Worker) = main.post { workerRows.remove(w)?.let(::removeRow) }

    /**
     * Main thread: one row per request waiting, in queue order ("הבא בתור", "מקום 3 בתור"),
     * each with a cancel button. A request being worked on already has its progress row.
     */
    private fun refreshWaiting() {
        if (anchor == null) return
        val queue = ProcessingStatus.queue.value
        val busy = workerRows.values.toSet()
        val now = System.currentTimeMillis()
        val want = LinkedHashMap<Int, Notification>()
        queue.forEachIndexed { i, r ->
            val id = Notifications.rowId(r.stage, r.callId, r.memoId)
            if (id in busy) return@forEachIndexed
            val place = queue.filter { it.stage == r.stage }.indexOfFirst { it.sameItem(r) } + 1
            val label = if (r.isMemo) null else labels[r.callId] ?: run { fetchLabel(r.callId); null }
            // Shade order: newer first, so earlier places get later times.
            want[id] = Notifications.waiting(this, id, r, label, place, sortTime = now - (i + 1) * 1_000L)
        }
        (waitingRows - want.keys).filter { it !in busy }.forEach(::removeRow)
        waitingRows.clear()
        waitingRows += want.keys
        want.forEach { (id, n) -> putRow(id, n) }
    }

    private fun fetchLabel(callId: Long) {
        val dao = (application as CallNotesApp).database.calls()
        scope.launch {
            val call = dao.get(callId) ?: return@launch
            if (labels.putIfAbsent(callId, Notifications.callLabel(call)) == null) main.post { refreshWaiting() }
        }
    }

    /** Main thread: adds or updates a row; the first real row takes over the "מכין…" one. */
    private fun putRow(id: Int, n: Notification) {
        rows[id] = n
        val old = anchor
        if (old == null || old == Notifications.ID_SERVICE) {
            runCatching { goForeground(id, n); anchor = id }
            if (old != null && anchor == id) nm().cancel(old)
        } else {
            notify(id, n)
        }
    }

    /** Main thread: removes a row. The service keeps a foreground row while it runs, so it moves first. */
    private fun removeRow(id: Int) {
        rows.remove(id)
        if (anchor != id) { nm().cancel(id); return }
        val (next, n) = rows.entries.firstOrNull()?.toPair() ?: (Notifications.ID_SERVICE to Notifications.service(this))
        runCatching { goForeground(next, n); anchor = next }
        nm().cancel(id)
    }

    /**
     * Voice notes waiting in the database join the queue in the order they were recorded
     * (the queue lives in memory; after a restart the notes are still there).
     */
    suspend fun syncMemos() {
        val dao = (application as CallNotesApp).database.calls()
        for ((state, stage) in listOf(MemoState.RECORDED to Stage.TRANSCRIBE, MemoState.TRANSCRIBED to Stage.SUMMARY)) {
            val memos = dao.memosIn(state)
            val busy = setOfNotNull(transcribe.memoId, summary.memoId)
            memos.filter { it.id !in busy }.forEach { ProcessingStatus.enqueue(Request(stage, memoId = it.id, at = it.createdAt)) }
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
        private const val EXTRA_MEMO = "memo"
        const val ACTION_STOP = "com.callnotes.action.STOP_TRANSCRIPTION"

        @Volatile private var instance: TranscriptionService? = null

        /**
         * Cancels one call everywhere, at once: out of both queues, its progress gone from
         * the UI, the database updated (held / summary skipped; an older transcript comes
         * back), and the engine working on it aborted.
         */
        fun cancelCall(context: Context, id: Long) {
            ProcessingStatus.removeCall(id)
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

        /**
         * Turns waiting voice notes into tasks: each joins the queue in the order recorded,
         * like any request (nothing in progress is stopped for it). Not held back by the
         * automatic switches or "only while charging" — the user asked for it — but battery
         * saver (when set to pause) still applies. Returns false if it couldn't start.
         */
        fun startMemo(context: Context): Boolean {
            val app = context.applicationContext as CallNotesApp
            if (BatterySaver.paused(context)) {
                note(context, "מושהה — מצב חיסכון בסוללה פעיל. ההקלטה תהפוך למשימה כשיכבה.")
                return false
            }
            if (app.models.readyModel() == null || app.models.readyModel(ModelRole.ANALYZE) == null) {
                note(context, "כדי להפוך הקלטה למשימה צריך מודל תמלול ומודל סיכום — הורד אותם בהגדרות")
                return false
            }
            return try {
                ContextCompat.startForegroundService(context,
                    Intent(context, TranscriptionService::class.java).putExtra(EXTRA_MEMO, true))
                true
            } catch (e: Exception) {
                ProcessingLog.write(context, "service start refused (voice note): ${describe(e)}")
                note(context, "העיבוד לא הופעל (${e.javaClass.simpleName}). ינסה שוב כשהאפליקציה תיפתח.")
                false
            }
        }

        /** A voice note was deleted: out of the queue, and stopped if either worker is on it. */
        fun cancelMemo(id: Long) {
            ProcessingStatus.removeMemo(id)
            if (ProcessingStatus.state.value.memoId == id) ProcessingStatus.set(ProcessingStatus.State(running = true))
            if (ProcessingStatus.summary.value.memoId == id) ProcessingStatus.setSummary(ProcessingStatus.State(running = true))
            Handler(Looper.getMainLooper()).post {
                instance?.transcribe?.cancelMemo(id)
                instance?.summary?.cancelMemo(id)
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
            ProcessingStatus.clearQueue()
            ProcessingStatus.set(ProcessingStatus.State())
            ProcessingStatus.setSummary(ProcessingStatus.State())
            ProcessingStatus.touch()
            Handler(Looper.getMainLooper()).post { instance?.stopAllNow() }
        }

        /**
         * Starts processing if a model is ready. Safe to call often. [callId] / [analyseId]:
         * the user asked to transcribe / summarise that call — it joins the queue and runs
         * when its turn comes (after what is in progress and earlier requests, before
         * automatic work). Returns false (and records why in the "last run" note) if it
         * couldn't start.
         */
        fun start(context: Context, afterCall: Boolean = false, callId: Long? = null, analyseId: Long? = null): Boolean {
            val app = context.applicationContext as CallNotesApp
            if (BatterySaver.paused(context)) {
                note(context, "מושהה — מצב חיסכון בסוללה פעיל")
                return false
            }
            // Automatic start: only if some automatic step may run now (switched on, and
            // not waiting for the charger). Explicit requests always run.
            if (callId == null && analyseId == null &&
                !Energy.autoTranscribeAllowed(context) && !Energy.autoSummaryAllowed(context)
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
                    if (callId != null) ProcessingStatus.removeCall(callId)
                    note(context, "אין מודל תמלול מותקן — התמלול לא הופעל")
                    return false
                }
            }
            if (analyseId != null) {
                ProcessingStatus.requestSummary(analyseId)
                instance?.summary?.cancelled?.remove(analyseId)
            }
            if (callId != null) {
                ProcessingStatus.requestTranscription(callId)
                instance?.transcribe?.cancelled?.remove(callId)
            }
            return try {
                ContextCompat.startForegroundService(context,
                    Intent(context, TranscriptionService::class.java).putExtra(EXTRA_AFTER_CALL, afterCall))
                true
            } catch (e: Exception) {
                // e.g. background start not allowed; the user can ask again from the app.
                (callId ?: analyseId)?.let(ProcessingStatus::removeCall)
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
            runCatching { Hashing.sha256Hex(file).take(12) }.getOrDefault("unreadable")

        /** Exception with its first app/library frames, for the log. */
        internal fun describe(e: Throwable): String {
            val frames = e.stackTrace.take(4).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
            val cause = e.cause?.let { " | cause ${it.javaClass.simpleName}: ${it.message}" } ?: ""
            return "${e.javaClass.name}: ${e.message}$cause @ $frames"
        }
    }
}
