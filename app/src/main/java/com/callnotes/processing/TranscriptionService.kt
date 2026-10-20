package com.callnotes.processing

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.callnotes.CallNotesApp
import com.callnotes.audio.AudioDecoder
import com.callnotes.data.CallEntity
import com.callnotes.data.CallStatus
import com.callnotes.data.SegmentEntity
import com.callnotes.inference.GemmaTranscriber
import com.callnotes.inference.Transcriber
import com.callnotes.util.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Works through the transcription queue (calls with status `recorded`) in a foreground
 * service, newest first, then stops. Loads the model once per run and releases it at the
 * end. Pauses while the phone is overheating.
 */
class TranscriptionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = when {
            Build.VERSION.SDK_INT >= 35 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            Build.VERSION.SDK_INT >= 29 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else -> 0
        }
        try {
            ServiceCompat.startForeground(this, Notifications.ID_PROCESSING,
                Notifications.processing(this, null), type)
        } catch (e: Exception) {
            note("לא ניתן היה להפעיל את שירות התמלול: ${e.javaClass.simpleName}")
            stopSelf()
            return START_NOT_STICKY
        }
        if (job?.isActive != true) {
            val delayFirst = intent?.getBooleanExtra(EXTRA_AFTER_CALL, false) == true
            job = scope.launch { runQueue(delayFirst) }
        }
        return START_NOT_STICKY
    }

    private suspend fun runQueue(delayFirst: Boolean) {
        val app = application as CallNotesApp
        val dao = app.database.calls()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "callnotes:transcribe")
            .apply { acquire(3 * 60 * 60 * 1000L) }
        var transcriber: Transcriber? = null
        try {
            // A call that just ended is still being saved; give it a moment to land.
            if (delayFirst) delay(6_000)
            dao.failInterrupted()
            val (spec, file) = app.models.readyModel() ?: run { note("אין מודל מותקן"); return }

            var done = 0
            var failed = 0
            while (true) {
                val call = dao.nextPending() ?: break
                waitWhileHot()
                if (transcriber == null) {
                    ProcessingStatus.set(ProcessingStatus.State(running = true, phase = "loading"))
                    transcriber = GemmaTranscriber(this, spec, file)
                }
                if (process(call, transcriber)) done++ else failed++
            }
            note(when {
                done == 0 && failed == 0 -> "אין הקלטות שממתינות לתמלול"
                failed == 0 -> "תומללו $done הקלטות (${spec.displayName})"
                else -> "תומללו $done, נכשלו $failed (${spec.displayName})"
            })
        } finally {
            transcriber?.close()
            ProcessingStatus.set(ProcessingStatus.State())
            wakeLock?.takeIf { it.isHeld }?.release()
            stopSelf()
        }
    }

    /** Returns true if the call was transcribed. */
    private suspend fun process(call: CallEntity, transcriber: Transcriber): Boolean {
        val dao = (application as CallNotesApp).database.calls()
        dao.setStatus(call.id, CallStatus.TRANSCRIBING, null, null)
        val converted = File(cacheDir, "convert_${call.id}.wav")
        try {
            val source = File(call.filePath!!)
            val audio = AudioDecoder.ensureWorkingFormat(source, converted)
            val transcript = transcriber.transcribe(audio) { done, total ->
                ProcessingStatus.set(ProcessingStatus.State(true, call.id, done, total, "transcribing"))
                Notifications.updateProcessing(this, "$done/$total")
            }
            val rows = transcript.segments.mapIndexed { i, s ->
                SegmentEntity(callId = call.id, idx = i, startMs = s.startMs, endMs = s.endMs, text = s.text)
            }
            dao.replaceTranscript(call.id, rows, transcriber.modelId)
            return true
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) {
                dao.setStatus(call.id, CallStatus.RECORDED, null, null)
                throw e
            }
            dao.setStatus(call.id, CallStatus.TRANSCRIBE_FAILED, null,
                "${e.javaClass.simpleName}: ${e.message ?: ""}".take(300))
            return false
        } finally {
            converted.delete()
        }
    }

    private suspend fun waitWhileHot() {
        val power = getSystemService(PowerManager::class.java)
        while (power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
            ProcessingStatus.set(ProcessingStatus.State(running = true, phase = "waiting_thermal"))
            delay(60_000)
        }
    }

    private fun note(text: String) = note(this, text)

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_AFTER_CALL = "after_call"

        /** Starts processing if a model is ready. Safe to call often. */
        fun start(context: Context, afterCall: Boolean = false): Boolean {
            val app = context.applicationContext as CallNotesApp
            if (app.models.readyModel() == null) {
                note(context, "אין מודל מותקן — התמלול לא הופעל")
                return false
            }
            return try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, TranscriptionService::class.java).putExtra(EXTRA_AFTER_CALL, afterCall),
                )
                true
            } catch (e: Exception) {
                // e.g. background start not allowed; picked up when the app opens.
                note(context, "התמלול יתחיל כשהאפליקציה תיפתח (${e.javaClass.simpleName})")
                false
            }
        }

        /** Records the outcome of the last run where the settings screen can show it. */
        fun note(context: Context, text: String) {
            val time = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.US).format(java.util.Date())
            com.callnotes.util.Prefs(context).lastProcessing = "$time · $text"
            ProcessingStatus.touch()
        }
    }
}
