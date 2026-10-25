package com.callnotes.inference.whisper

import com.callnotes.audio.Wav
import com.callnotes.inference.ModelSpec
import com.callnotes.inference.Transcriber
import com.callnotes.inference.Transcript
import com.callnotes.inference.TranscriptText
import com.callnotes.util.CpuInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hebrew transcription with ivrit.ai's Whisper large-v3-turbo through whisper.cpp,
 * fully on the device. Whisper handles the 30 s windowing itself, so the whole call goes
 * in at once. Beam search 5, word timing via DTW for the speaker step. Speech Whisper
 * skipped is read a second time ([SpeechGaps]); loops are trimmed, not whole lines.
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
        vocabulary: String,
        onProgress: (Int, Int) -> Unit,
    ): Transcript = withContext(Dispatchers.IO) {
        load()
        val samples = Wav.readFloats(audio)
        onProgress(0, 100)
        val first = clean(decode(samples, languageHint, vocabulary, keepQuiet = false) { onProgress(it * 9 / 10, 100) })

        // Second look at speech Whisper dropped (a window judged "no speech", e.g. hold music
        // before a faint voice): each stretch alone, never dropped as silence.
        val gaps = SpeechGaps.find(samples, first.words, first.segments)
        val segments = first.segments.toMutableList()
        val words = first.words.toMutableList()
        var recovered = 0L
        gaps.forEachIndexed { k, g ->
            ensureActive()
            val from = (g.first * 16).toInt().coerceIn(0, samples.size)
            val to = (g.last * 16).toInt().coerceIn(from, samples.size)
            val piece = clean(decode(samples.copyOfRange(from, to), languageHint, vocabulary, keepQuiet = true) {})
            val kept = piece.segments.filterNot { TranscriptText.isFiller(it.text) }
            if (kept.isNotEmpty()) {
                recovered += g.last - g.first
                segments += kept.map { it.copy(startMs = it.startMs + g.first, endMs = it.endMs + g.first) }
                words += piece.words
                    .filter { w -> kept.any { w.startMs < it.endMs + 200 && w.endMs > it.startMs - 200 } }
                    .map { it.copy(startMs = it.startMs + g.first, endMs = it.endMs + g.first) }
            }
            onProgress(90 + (k + 1) * 10 / gaps.size, 100)
        }
        onProgress(100, 100)
        Transcript(
            // Drop the dialer's "call is being recorded" notice (first lines only).
            TranscriptText.stripAnnouncements(segments.sortedBy { it.startMs }),
            words.sortedBy { it.startMs },
            secondLook = gaps.size,
            recoveredMs = recovered,
        )
    }

    /** One whisper.cpp run over [samples]; cancelling the coroutine aborts it within moments. */
    private suspend fun decode(samples: FloatArray, language: String, vocabulary: String, keepQuiet: Boolean, onPercent: (Int) -> Unit): Transcript {
        val finished = AtomicBoolean(false)
        val raw = coroutineScope {
            val watcher = launch {
                try { awaitCancellation() } finally { if (!finished.get()) WhisperNative.abort(handle) }
            }
            try {
                WhisperNative.transcribe(handle, samples, language, threads, beamSize, vocabulary, keepQuiet) { p ->
                    onPercent(p.coerceIn(0, 100))
                }
            } finally {
                finished.set(true)
                watcher.cancel()
            }
        }
        currentCoroutineContext().ensureActive() // aborted because the coroutine was cancelled
        return WhisperOutput.parse(raw ?: error("whisper failed"))
    }

    /** Whisper's loops trimmed (repeats only, not the words around them); empty lines dropped. */
    private fun clean(t: Transcript) = Transcript(
        t.segments.map { it.copy(text = TranscriptText.withoutRepeats(it.text)) }.filter { it.text.isNotBlank() },
        TranscriptText.withoutRepeatedWords(t.words),
    )

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
