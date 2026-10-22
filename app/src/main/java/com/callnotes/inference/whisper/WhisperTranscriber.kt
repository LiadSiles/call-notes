package com.callnotes.inference.whisper

import com.callnotes.audio.Wav
import com.callnotes.inference.ModelSpec
import com.callnotes.inference.Transcriber
import com.callnotes.inference.Transcript
import com.callnotes.inference.TranscriptMerger
import com.callnotes.util.CpuInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hebrew transcription with ivrit.ai's Whisper large-v3-turbo through whisper.cpp,
 * fully on the device. Whisper handles the 30 s windowing itself, so the whole call goes
 * in at once. Beam search 5, no cross-window context (one bad window can't derail the
 * rest), word timing via DTW for the speaker step.
 */
class WhisperTranscriber(
    private val spec: ModelSpec,
    private val modelFile: File,
    private val beamSize: Int = 5,
    /** "q8_0" etc. when running on a quantized copy; part of the id saved with each transcript. */
    private val variant: String? = null,
) : Transcriber {

    override val modelId: String get() = listOfNotNull(spec.id, variant, "beam$beamSize".takeIf { beamSize != 5 }).joinToString("+")

    @Volatile private var handle = 0L

    /** Threads used, for the log. */
    val threads: Int = CpuInfo.inferenceThreads()

    override suspend fun load() = withContext(Dispatchers.IO) {
        if (handle != 0L) return@withContext
        WhisperNative.load()
        var h = WhisperNative.init(modelFile.absolutePath, true, WhisperNative.AHEADS_LARGE_V3_TURBO)
        if (h == 0L) h = WhisperNative.init(modelFile.absolutePath, false, 0) // without DTW
        check(h != 0L) { "whisper could not load ${modelFile.name}" }
        handle = h
    }

    override suspend fun transcribe(
        audio: File,
        languageHint: String,
        onProgress: (Int, Int) -> Unit,
    ): Transcript = withContext(Dispatchers.IO) {
        load()
        val info = Wav.readInfo(audio) ?: error("Not a WAV file: ${audio.name}")
        require(info.isWorkingFormat) { "Expected 16 kHz mono PCM16, got $info" }
        val pcm = Wav.readSamples(audio, info, 0, info.sampleCount.toInt())
        val samples = FloatArray(pcm.size) { pcm[it] / 32768f }
        onProgress(0, 100)

        val finished = AtomicBoolean(false)
        val raw = coroutineScope {
            // Cancelling the coroutine aborts the native run at its next check.
            val watcher = launch {
                try { awaitCancellation() } finally { if (!finished.get()) WhisperNative.abort(handle) }
            }
            try {
                WhisperNative.transcribe(handle, samples, languageHint, threads, beamSize) { p ->
                    onProgress(p.coerceIn(0, 100), 100)
                }
            } finally {
                finished.set(true)
                watcher.cancel()
            }
        }
        ensureActive() // aborted because the coroutine was cancelled
        raw ?: error("whisper failed")
        onProgress(100, 100)

        val t = WhisperOutput.parse(raw)
        // Same clean-up as before: drop the dialer's "call is being recorded" notice and loops.
        val segments = TranscriptMerger.stripAnnouncements(
            t.segments.filterNot { TranscriptMerger.looksLikeLoop(it.text) }
        )
        Transcript(segments, t.words)
    }

    /** whisper.cpp checks this flag between graph nodes: returns within moments. */
    override fun abort() {
        val h = handle
        if (h != 0L) WhisperNative.abort(h)
    }

    override fun close() {
        if (handle != 0L) WhisperNative.free(handle)
        handle = 0L
    }
}
