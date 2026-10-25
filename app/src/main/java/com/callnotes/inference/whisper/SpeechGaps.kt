package com.callnotes.inference.whisper

import com.callnotes.inference.Transcript
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Finds stretches of the recording that sound like speech but got no words from Whisper.
 *
 * Whisper judges each 30 s window as a whole: when a window starts with something that
 * isn't speech (hold music, a ring tone) and the speech after it is faint (the other side
 * through the earpiece), it can decide the window is "no speech" and drop everything in
 * it — the first seconds of what the other person said disappear. These stretches are
 * read a second time on their own ([WhisperTranscriber]).
 *
 * Speech here is plain energy: 100 ms frames clearly above the recording's own noise floor.
 * Pure (unit-tested).
 */
object SpeechGaps {

    private const val FRAME_MS = 100
    /** A frame counts as sound this far above the floor (10th percentile of frame levels). */
    private const val ABOVE_FLOOR_DB = 12.0
    /** …and above this absolute level (digital near-silence never counts). */
    private const val MIN_DB = -65.0
    /** Short dips inside one stretch (between words) are bridged. */
    private const val BRIDGE_FRAMES = 4
    /** A stretch needs this much sound to be worth a second look. */
    private const val MIN_SOUND_MS = 1_000L
    /** Stretches closer than this are read together. */
    private const val JOIN_MS = 1_500L
    /** Context around a stretch. */
    private const val PAD_MS = 400L
    /** Longest piece read at once (Whisper's window is 30 s). */
    const val MAX_PIECE_MS = 28_000L

    fun find(samples: FloatArray, words: List<Transcript.Word>, segments: List<Transcript.Segment>, sampleRate: Int = 16_000): List<LongRange> {
        val perFrame = sampleRate * FRAME_MS / 1000
        val n = samples.size / perFrame
        if (n == 0) return emptyList()
        val db = DoubleArray(n) { f ->
            var s = 0.0
            for (i in f * perFrame until (f + 1) * perFrame) s += samples[i].toDouble() * samples[i]
            val rms = sqrt(s / perFrame)
            if (rms <= 1e-7) -140.0 else 20 * log10(rms)
        }
        val floor = db.sorted()[(n * 0.1).toInt().coerceAtMost(n - 1)]
        val sound = BooleanArray(n) { db[it] > maxOf(floor + ABOVE_FLOOR_DB, MIN_DB) }

        // Covered: inside a word (or, without word timing, a segment), with a little slack.
        val covered = BooleanArray(n)
        val spans = if (words.isNotEmpty()) words.map { it.startMs to it.endMs } else segments.map { it.startMs to it.endMs }
        for ((a, b) in spans) {
            val from = ((a - 300) / FRAME_MS).toInt().coerceIn(0, n)
            val to = ((b + 300) / FRAME_MS).toInt().coerceIn(from, n)
            for (f in from until to) covered[f] = true
        }

        // Runs of uncovered sound, bridging short dips.
        val runs = ArrayList<IntRange>()
        var start = -1
        var lastSound = -1
        var soundFrames = 0
        fun close() {
            if (start >= 0 && soundFrames * FRAME_MS >= MIN_SOUND_MS) runs += start..lastSound
            start = -1; soundFrames = 0
        }
        for (f in 0 until n) {
            val s = sound[f] && !covered[f]
            if (s) {
                if (start < 0) start = f
                lastSound = f
                soundFrames++
            } else if (start >= 0 && (covered[f] || f - lastSound > BRIDGE_FRAMES)) {
                close()
            }
        }
        close()

        // Join near runs, pad, split long ones into pieces Whisper reads in one window.
        val joined = ArrayList<LongRange>()
        for (r in runs) {
            val a = r.first.toLong() * FRAME_MS
            val b = (r.last + 1).toLong() * FRAME_MS
            val prev = joined.lastOrNull()
            if (prev != null && a - prev.last <= JOIN_MS) joined[joined.lastIndex] = prev.first..b else joined += a..b
        }
        val total = n.toLong() * FRAME_MS
        return joined.flatMap { r ->
            val a = (r.first - PAD_MS).coerceAtLeast(0)
            val b = (r.last + PAD_MS).coerceAtMost(total)
            (a until b step MAX_PIECE_MS).map { s -> s..minOf(b, s + MAX_PIECE_MS) }
        }
    }
}
