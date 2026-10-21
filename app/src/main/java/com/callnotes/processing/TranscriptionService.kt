package com.callnotes.processing

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
import com.callnotes.audio.AudioDecoder
import com.callnotes.data.CallDao
import com.callnotes.data.CallEntity
import com.callnotes.data.CallStatus
import com.callnotes.data.SegmentEntity
import com.callnotes.inference.GemmaTranscriber
import com.callnotes.inference.ModelSpec
import com.callnotes.inference.Transcriber
import com.callnotes.util.Notifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Works through the transcription queue in a foreground service, then stops.
 *
 * Order: calls the user asked for by hand first (and a manual request pauses an
 * automatic run on another call — that call goes back to the queue), then the rest,
 * newest first. The model is loaded once per run and released at the end. Pauses while
 * the phone is overheating. Every step goes to [ProcessingLog].
 */
class TranscriptionService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private val crashHandler = CoroutineExceptionHandler { _, e ->
        ProcessingLog.end(this, "run crashed: ${describe(e)}")
        note("התמלול נעצר בשגיאה: ${e.javaClass.simpleName}")
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + crashHandler)
    private var wakeLock: PowerManager.WakeLock? = null

    /** Main-thread only. True from launching a run until its cleanup finished. */
    private var running = false
    private var lastStartId = 0

    /** The call being transcribed now, so a manual request can pre-empt it. */
    @Volatile private var current: Job? = null
    @Volatile private var currentCallId: Long? = null
    @Volatile private var currentRequested = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        try {
            goForeground()
        } catch (e: Exception) {
            ProcessingLog.write(this, "startForeground refused: ${describe(e)}")
            note("לא ניתן היה להפעיל את שירות התמלול: ${e.javaClass.simpleName}")
            ProcessingStatus.clearRequests()
            if (!running) stopSelf()
            return START_NOT_STICKY
        }

        val requestedId = intent?.getLongExtra(EXTRA_CALL_ID, -1L)?.takeIf { it > 0 }
        if (requestedId != null && running && !currentRequested && currentCallId != requestedId) {
            // An automatic run is busy with another call: pause it, the request goes first.
            current?.cancel()
        }
        if (!running) {
            running = true
            val delayFirst = intent?.getBooleanExtra(EXTRA_AFTER_CALL, false) == true
            scope.launch { runQueue(delayFirst) }
        }
        return START_NOT_STICKY
    }

    /**
     * Enters the foreground with an explicit type (required from API 34). Calls the
     * platform method directly: `ServiceCompat.startForeground` masks the type with the
     * set it knows (API 34), which drops `mediaProcessing` (API 35) to "none" — Android
     * then refuses with InvalidForegroundServiceTypeException (0.3.5 bug). Falls back to
     * dataSync if the media-processing type is refused.
     */
    private fun goForeground() {
        val notification = Notifications.processing(this, null)
        if (Build.VERSION.SDK_INT >= 35) {
            try {
                startForeground(Notifications.ID_PROCESSING, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
                return
            } catch (e: Exception) {
                ProcessingLog.write(this, "mediaProcessing refused, trying dataSync: ${describe(e)}")
            }
        }
        startForeground(Notifications.ID_PROCESSING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private suspend fun runQueue(delayFirst: Boolean) {
        val app = application as CallNotesApp
        val dao = app.database.calls()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "callnotes:transcribe")
            .apply { acquire(3 * 60 * 60 * 1000L) }
        var transcriber: Transcriber? = null
        try {
            ProcessingLog.step(this, "run start · ${ProcessingLog.deviceLine(this)}")
            // A call that just ended is still being saved; give it a moment to land.
            if (delayFirst && ProcessingStatus.firstRequested() == null) delay(6_000)
            dao.failInterrupted()
            val (spec, file) = app.models.readyModel() ?: run {
                note("אין מודל מותקן")
                ProcessingLog.end(this, "run end · no model installed")
                return
            }

            var done = 0
            var failed = 0
            var loadFailed = false
            while (true) {
                val (call, requested) = nextCall(dao) ?: break
                waitWhileHot()
                if (transcriber == null) {
                    transcriber = loadModel(spec, file, call, dao)
                    if (transcriber == null) { loadFailed = true; break }
                }
                currentCallId = call.id
                currentRequested = requested
                val t = checkNotNull(transcriber)
                val work = scope.async { process(call, t) }
                current = work
                val ok = try {
                    work.await()
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive() // the service itself is stopping
                    null // paused for a manual request
                } finally {
                    current = null
                    currentCallId = null
                }
                when (ok) {
                    true -> done++
                    false -> failed++
                    null -> ProcessingLog.write(this, "call ${call.id}: paused, a manual request goes first")
                }
            }
            if (!loadFailed) note(when {
                done == 0 && failed == 0 -> "אין הקלטות שממתינות לתמלול"
                failed == 0 -> "תומללו $done הקלטות (${spec.displayName})"
                else -> "תומללו $done, נכשלו $failed (${spec.displayName})"
            })
            ProcessingLog.end(this, "run end · transcribed $done, failed $failed")
        } finally {
            transcriber?.close()
            ProcessingStatus.set(ProcessingStatus.State())
            wakeLock?.takeIf { it.isHeld }?.release()
            main.post { finishRun() }
        }
    }

    /** Main thread: start another run if a request slipped in at the very end, else stop. */
    private fun finishRun() {
        running = false
        if (ProcessingStatus.firstRequested() != null) {
            running = true
            scope.launch { runQueue(false) }
        } else {
            stopSelf(lastStartId)
        }
    }

    /** Next call to transcribe: manual requests first, then the newest pending one. */
    private suspend fun nextCall(dao: CallDao): Pair<CallEntity, Boolean>? {
        while (true) {
            val id = ProcessingStatus.firstRequested() ?: break
            ProcessingStatus.unrequest(id)
            val call = dao.get(id)
            if (call != null && call.filePath != null && call.status == CallStatus.RECORDED) return call to true
        }
        return dao.nextPending()?.let { it to false }
    }

    /** Loads the model, showing it on [call]. On failure marks that call failed and returns null. */
    private suspend fun loadModel(spec: ModelSpec, file: File, call: CallEntity, dao: CallDao): Transcriber? {
        ProcessingStatus.set(ProcessingStatus.State(running = true, callId = call.id, phase = "loading"))
        ProcessingLog.step(this, "loading model ${spec.id} (${"%.2f".format(file.length() / 1e9)} GB) · ${ProcessingLog.freeRam(this)}")
        val started = System.currentTimeMillis()
        val t = GemmaTranscriber(this, spec, file)
        return try {
            t.load()
            ProcessingLog.write(this, "model loaded in ${secs(started)} · ${ProcessingLog.freeRam(this)}")
            t
        } catch (e: Throwable) {
            runCatching { t.close() }
            if (e is CancellationException) throw e
            ProcessingLog.write(this, "model load FAILED after ${secs(started)}: ${describe(e)}")
            dao.setStatus(call.id, CallStatus.TRANSCRIBE_FAILED, null,
                "טעינת המודל נכשלה: ${e.javaClass.simpleName} ${e.message ?: ""}".take(300))
            note("טעינת המודל נכשלה: ${e.javaClass.simpleName}")
            null
        }
    }

    /** Returns true if the call was transcribed. */
    private suspend fun process(call: CallEntity, transcriber: Transcriber): Boolean {
        val dao = (application as CallNotesApp).database.calls()
        dao.setStatus(call.id, CallStatus.TRANSCRIBING, null, null)
        ProcessingStatus.set(ProcessingStatus.State(true, call.id, 0, 0, "transcribing"))
        val converted = File(cacheDir, "convert_${call.id}.wav")
        val started = System.currentTimeMillis()
        try {
            val source = File(call.filePath!!)
            ProcessingLog.step(this, "call ${call.id}: preparing audio (${source.extension}, ${"%.1f".format(source.length() / 1e6)} MB)")
            val audio = AudioDecoder.ensureWorkingFormat(source, converted)
            var chunkStart = System.currentTimeMillis()
            val transcript = transcriber.transcribe(audio) { done, total ->
                if (done == 0) {
                    ProcessingLog.write(this, "call ${call.id}: ${total} chunks")
                } else {
                    ProcessingLog.write(this, "call ${call.id}: chunk $done/$total in ${secs(chunkStart)}")
                }
                chunkStart = System.currentTimeMillis()
                if (done < total) ProcessingLog.step(this, "call ${call.id}: chunk ${done + 1}/$total")
                ProcessingStatus.set(ProcessingStatus.State(true, call.id, done, total, "transcribing"))
                Notifications.updateProcessing(this, "$done/$total")
            }
            val rows = transcript.segments.mapIndexed { i, s ->
                SegmentEntity(callId = call.id, idx = i, startMs = s.startMs, endMs = s.endMs, text = s.text)
            }
            dao.replaceTranscript(call.id, rows, transcriber.modelId)
            ProcessingLog.write(this, "call ${call.id}: done in ${secs(started)}, ${rows.size} segments, " +
                "${rows.sumOf { it.text.length }} chars")
            return true
        } catch (e: Throwable) {
            if (e is CancellationException) {
                withContext(NonCancellable) { dao.setStatus(call.id, CallStatus.RECORDED, null, null) }
                throw e
            }
            ProcessingLog.write(this, "call ${call.id}: FAILED after ${secs(started)}: ${describe(e)}")
            dao.setStatus(call.id, CallStatus.TRANSCRIBE_FAILED, null,
                "${e.javaClass.simpleName}: ${e.message ?: ""}".take(300))
            return false
        } finally {
            converted.delete()
        }
    }

    private suspend fun waitWhileHot() {
        val power = getSystemService(PowerManager::class.java)
        var logged = false
        while (power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
            if (!logged) { ProcessingLog.step(this, "waiting for the phone to cool down"); logged = true }
            ProcessingStatus.set(ProcessingStatus.State(running = true, phase = "waiting_thermal"))
            delay(60_000)
        }
    }

    private fun note(text: String) = note(this, text)

    override fun onDestroy() {
        scope.cancel()
        ProcessingStatus.set(ProcessingStatus.State())
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_AFTER_CALL = "after_call"
        private const val EXTRA_CALL_ID = "call_id"

        /**
         * Starts processing if a model is ready. Safe to call often. With [callId] that
         * call is transcribed next, ahead of everything else. Returns false (and records
         * why in the "last run" note) if it couldn't start.
         */
        fun start(context: Context, afterCall: Boolean = false, callId: Long? = null): Boolean {
            val app = context.applicationContext as CallNotesApp
            if (app.models.readyModel() == null) {
                if (callId != null) ProcessingStatus.unrequest(callId)
                note(context, "אין מודל מותקן — התמלול לא הופעל")
                return false
            }
            if (callId != null) ProcessingStatus.request(callId)
            return try {
                val intent = Intent(context, TranscriptionService::class.java)
                    .putExtra(EXTRA_AFTER_CALL, afterCall)
                if (callId != null) intent.putExtra(EXTRA_CALL_ID, callId)
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (e: Exception) {
                // e.g. background start not allowed; picked up when the app opens.
                if (callId != null) ProcessingStatus.unrequest(callId)
                ProcessingLog.write(context, "service start refused: ${describe(e)}")
                note(context, "התמלול לא הופעל (${e.javaClass.simpleName}). ינסה שוב כשהאפליקציה תיפתח.")
                false
            }
        }

        /** Records the outcome of the last run where the settings screen can show it. */
        fun note(context: Context, text: String) {
            val time = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.US).format(java.util.Date())
            com.callnotes.util.Prefs(context).lastProcessing = "$time · $text"
            ProcessingStatus.touch()
        }

        private fun secs(since: Long) = "%.1f s".format((System.currentTimeMillis() - since) / 1000.0)

        /** Exception with its first app/library frames, for the log. */
        internal fun describe(e: Throwable): String {
            val frames = e.stackTrace.take(4).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
            val cause = e.cause?.let { " | cause ${it.javaClass.simpleName}: ${it.message}" } ?: ""
            return "${e.javaClass.name}: ${e.message}$cause @ $frames"
        }
    }
}
