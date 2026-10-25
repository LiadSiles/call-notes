package com.callnotes.util

import android.content.Context
import androidx.core.content.ContextCompat
import com.callnotes.CallNotesApp
import com.callnotes.audio.Wav
import com.callnotes.capture.WavRecorder
import com.callnotes.data.MemoState
import com.callnotes.data.VoiceMemoEntity
import com.callnotes.processing.TranscriptionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "Record a task": a short voice note that becomes a task — audio → Whisper → text →
 * Gemma → task, all on the phone. This object records the note and manages it before and
 * after the background workers ([com.callnotes.processing.TranscribeWorker] transcribes it,
 * [com.callnotes.processing.SummaryWorker] writes the task).
 *
 * The audio is a 16 kHz WAV in the app's private storage, kept only until it is
 * transcribed; then only the text remains, until the task exists.
 */
object VoiceMemos {

    /** Recording stops by itself after two minutes. */
    const val MAX_MS = 120_000L
    /** Shorter than this is treated as a mis-tap. */
    private const val MIN_MS = 800L
    /** A note whose loudest sample stays below this is silence (mic blocked or muted). */
    private const val SILENT_PEAK = 600

    private fun dir(context: Context) = File(context.filesDir, "memos").apply { mkdirs() }

    /** A voice note being recorded. */
    class Recording internal constructor(internal val file: File, private val recorder: WavRecorder) {
        private val startedAt = System.currentTimeMillis()
        val createdAt get() = startedAt
        /** Loudness of the last quarter second, 0..32768. */
        val level: Int get() = recorder.level
        val elapsedMs: Long get() = System.currentTimeMillis() - startedAt

        /** Blocks until the file is complete; returns the loudest sample. */
        internal fun stop(): Int {
            recorder.stop()
            recorder.awaitFinished()
            return recorder.peakLevel
        }
    }

    enum class Outcome { SAVED, TOO_SHORT, SILENT }

    /** Starts recording from the microphone; null if it couldn't start (in use, no permission). */
    fun start(context: Context): Recording? {
        val file = File(dir(context), "memo_${System.currentTimeMillis()}.wav")
        val recorder = WavRecorder(file, ContextCompat.getMainExecutor(context))
        if (!recorder.start()) { file.delete(); return null }
        return Recording(file, recorder)
    }

    /**
     * Stops [r] and, unless it is too short or silent, queues it to become a task (the
     * processing starts at once; see [TranscriptionService.startMemo]).
     */
    suspend fun finish(context: Context, r: Recording): Outcome = withContext(Dispatchers.IO) {
        val peak = r.stop()
        val duration = Wav.readInfo(r.file)?.durationMs ?: 0L
        when {
            duration < MIN_MS -> { r.file.delete(); Outcome.TOO_SHORT }
            peak < SILENT_PEAK -> { r.file.delete(); Outcome.SILENT }
            else -> {
                dao(context).insertMemo(
                    VoiceMemoEntity(createdAt = r.createdAt, path = r.file.absolutePath, durationMs = duration, state = MemoState.RECORDED)
                )
                TranscriptionService.startMemo(context)
                Outcome.SAVED
            }
        }
    }

    /** Stops [r] and throws the audio away. */
    suspend fun discard(r: Recording) = withContext(Dispatchers.IO) {
        r.stop()
        r.file.delete()
    }

    /** Picks up notes interrupted earlier (app closed, battery saver, stop all). */
    suspend fun resume(context: Context) {
        val dao = dao(context)
        val toTranscribe = dao.memoCount(MemoState.RECORDED) > 0
        if (toTranscribe || dao.memoCount(MemoState.TRANSCRIBED) > 0) {
            TranscriptionService.startMemo(context, needsTranscription = toTranscribe)
        }
    }

    /** Removes a note, stopping its processing if it is running now. */
    suspend fun delete(context: Context, memo: VoiceMemoEntity) {
        TranscriptionService.cancelMemo(memo.id)
        memo.path?.let { File(it).delete() }
        dao(context).deleteMemo(memo.id)
    }

    /** Tries a note again: transcribes the audio if it's still there, else writes the task from its text. */
    suspend fun retry(context: Context, memo: VoiceMemoEntity) {
        val state = when {
            memo.path != null && memo.text == null -> MemoState.RECORDED
            memo.text != null -> MemoState.TRANSCRIBED
            else -> return
        }
        dao(context).setMemo(memo.id, state, memo.path, memo.text, null)
        TranscriptionService.startMemo(context, needsTranscription = state == MemoState.RECORDED)
    }

    /**
     * Audio left behind by a recording that never became a note (the app was killed while
     * recording). Run at app start, when nothing can be recording.
     */
    suspend fun cleanUp(context: Context) {
        val files = dir(context).listFiles() ?: return
        if (files.isEmpty()) return
        val dao = dao(context)
        val kept = buildSet { files.forEach { f -> if (dao.memoWithPath(f.absolutePath) > 0) add(f) } }
        files.filter { it !in kept }.forEach { it.delete() }
    }

    private fun dao(context: Context) = (context.applicationContext as CallNotesApp).database.calls()
}
