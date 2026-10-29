package com.callnotes.inference.whisper

import com.callnotes.audio.Wav
import com.callnotes.inference.InferenceThreads
import com.callnotes.inference.ModelSpec
import com.callnotes.inference.Transcriber
import com.callnotes.inference.Transcript
import com.callnotes.inference.TranscriptText
import com.callnotes.util.CpuInfo
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Hebrew transcription with ivrit.ai's Whisper large-v3-turbo through whisper.cpp,
 * fully on the device. Whisper handles the 30 s windowing itself, so the whole call goes
 * in at once. Beam search 5, word timing via DTW for the speaker step. Speech Whisper
 * left out is read a second time ([MissedSpeech]); loops are trimmed, not whole lines.
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

    override suspend fun load() = withContext(InferenceThreads.dispatcher) {
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
    ): Transcript = withContext(InferenceThreads.dispatcher) {
        load()
        val samples = Wav.readFloats(audio)
        onProgress(0, 100)
        // A window can send Whisper into a loop: with beam search and its retries at higher
        // temperatures one 30 s window then takes many minutes (owner's call 39: no progress
        // in 5 min, where 30 s normally take ~1 min). Stuck → read the call again with one
        // plain decode per window, which can't run long.
        var stuck = false
        val first = clean(
            decode(samples, languageHint, vocabulary, keepQuiet = false, stallMs = STALL_MS) { onProgress(it * 9 / 10, 100) }
                ?: run {
                    stuck = true
                    onProgress(0, 100)
                    decode(samples, languageHint, vocabulary, keepQuiet = false, plain = true) { onProgress(it * 9 / 10, 100) }
                        ?: error("whisper got stuck")
                }
        )

        // Second look at speech Whisper left out (short answers, a window dropped as "no
        // speech", a loud word over a quiet one), packed into as few runs as possible.
        val runs = MissedSpeech.find(samples, first.words, first.segments)
        val packs = MissedSpeech.pack(runs, samples.size / 16L)
        val added = ArrayList<Transcript.Word>()
        packs.forEachIndexed { k, pack ->
            ensureActive()
            val out = clean(decode(MissedSpeech.buffer(samples, pack), languageHint, vocabulary, keepQuiet = true) {} ?: return@forEachIndexed)
            added += recovered(out.words, pack, runs, first.words + added)
            onProgress(90 + (k + 1) * 10 / packs.size, 100)
        }
        onProgress(100, 100)
        val segments = (first.segments + lines(added, runs)).sortedBy { it.startMs }
        Transcript(
            // Drop the dialer's "call is being recorded" notice (first lines only).
            TranscriptText.stripAnnouncements(segments),
            (first.words + added).sortedBy { it.startMs },
            secondLook = runs.size,
            recoveredMs = added.sumOf { it.endMs - it.startMs },
            stuckRetried = stuck,
        )
    }

    /**
     * Words of a second-look pack, back in the recording's time: only those inside a stretch
     * that was looked at (not the context around it), and not a word already there (same
     * text within 0.4 s).
     */
    private fun recovered(found: List<Transcript.Word>, pack: List<MissedSpeech.Piece>, runs: List<LongRange>, known: List<Transcript.Word>) =
        found.mapNotNull { w ->
            val a = MissedSpeech.toRecording(pack, w.startMs) ?: return@mapNotNull null
            val b = MissedSpeech.toRecording(pack, w.endMs) ?: (a + (w.endMs - w.startMs))
            val mid = (a + b) / 2
            if (runs.none { mid in (it.first - 150)..(it.last + 150) }) return@mapNotNull null
            val key = TranscriptText.normalize(w.text)
            if (key.isEmpty() || known.any { kotlin.math.abs((it.startMs + it.endMs) / 2 - mid) <= 400 && TranscriptText.normalize(it.text) == key }) {
                return@mapNotNull null
            }
            Transcript.Word(a, maxOf(a, b), w.text)
        }

    /**
     * Recovered words as transcript lines (for calls without speaker separation): words
     * within 0.7 s of each other form a line. A line that is only one of Whisper's stock
     * phrases for music or noise is not kept when it came from a long stretch (music on
     * hold); a short one is kept — it may well be a real "תודה".
     */
    private fun lines(words: List<Transcript.Word>, runs: List<LongRange>): List<Transcript.Segment> {
        val out = ArrayList<Transcript.Segment>()
        var group = ArrayList<Transcript.Word>()
        fun flush() {
            if (group.isEmpty()) return
            val text = group.joinToString(" ") { it.text }.trim()
            val run = runs.firstOrNull { r -> group.first().startMs <= r.last + 150 && group.last().endMs >= r.first - 150 }
            val long = run != null && run.last - run.first >= 3_000
            if (text.isNotEmpty() && !(long && TranscriptText.isFiller(text))) {
                out += Transcript.Segment(group.first().startMs, group.last().endMs, text)
            }
            group = ArrayList()
        }
        for (w in words.sortedBy { it.startMs }) {
            if (group.isNotEmpty() && w.startMs - group.last().endMs > 700) flush()
            group += w
        }
        flush()
        return out
    }

    /**
     * One whisper.cpp run over [samples]; cancelling the coroutine aborts it within moments.
     * [stallMs] > 0: if progress doesn't move for that long the run is stopped and null is
     * returned. [plain]: greedy, no temperature retries.
     */
    private suspend fun decode(
        samples: FloatArray,
        language: String,
        vocabulary: String,
        keepQuiet: Boolean,
        stallMs: Long = 0,
        plain: Boolean = false,
        onPercent: (Int) -> Unit,
    ): Transcript? {
        val finished = AtomicBoolean(false)
        val stalled = AtomicBoolean(false)
        val lastProgress = AtomicLong(System.currentTimeMillis())
        val raw = coroutineScope {
            val watcher = launch {
                try { awaitCancellation() } finally { if (!finished.get()) WhisperNative.abort(handle) }
            }
            val stallWatch = if (stallMs > 0) launch {
                while (!finished.get()) {
                    delay(5_000)
                    if (System.currentTimeMillis() - lastProgress.get() > stallMs) {
                        stalled.set(true)
                        WhisperNative.abort(handle)
                        break
                    }
                }
            } else null
            try {
                WhisperNative.transcribe(handle, samples, language, threads, if (plain) 1 else beamSize, vocabulary, keepQuiet, plain) { p ->
                    lastProgress.set(System.currentTimeMillis())
                    onPercent(p.coerceIn(0, 100))
                }
            } finally {
                finished.set(true)
                watcher.cancel()
                stallWatch?.cancel()
            }
        }
        currentCoroutineContext().ensureActive() // aborted because the coroutine was cancelled
        if (stalled.get()) return null
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

    private companion object {
        /** No progress for this long in one run: stuck (a 30 s window normally takes ~1 min). */
        const val STALL_MS = 4 * 60_000L
    }

    override fun close() {
        if (handle != 0L) WhisperNative.free(handle)
        handle = 0L
    }
}
