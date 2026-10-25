package com.callnotes.inference.whisper

import com.callnotes.inference.Transcript
import com.callnotes.inference.speakers.LevelSpeakers
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Finds speech Whisper left out, so it can be read a second time ([WhisperTranscriber]).
 * Whisper is trained on cleaned-up subtitles: it tends to skip short answers ("כן",
 * "בסדר", "אהה"), drops a whole 30 s window it judges "no speech", and hears one voice where
 * two overlap. Two kinds of stretch are looked at again:
 *
 * 1. **Sound without words:** 100 ms frames clearly above the recording's own noise floor
 *    that no word covers — even a single short word (≥ [MIN_SOUND_MS]).
 * 2. **A loud voice inside a quiet word:** on a call held to the ear the owner is ≥ 10 dB
 *    louder than the other side; frames at the owner's level inside the other side's words
 *    are the owner's "כן" said over their sentence, which Whisper merged away.
 *
 * The stretches are packed together, with silence between them, into as few ≤ 28 s
 * buffers as possible (whisper.cpp pays for a full 30 s window per call, however short the
 * audio), and the words found are mapped back to their place in the recording. Pure
 * (unit-tested).
 */
object MissedSpeech {

    private const val FRAME_MS = 100L
    private const val ABOVE_FLOOR_DB = 12.0
    private const val MIN_DB = -65.0
    /** A voice this much louder than the word under it is someone else talking over it. */
    const val LOUDER_DB = 10.0
    /** Short dips inside one stretch are bridged. */
    private const val BRIDGE_FRAMES = 2
    /** Shortest stretch worth a second look: one short word. */
    const val MIN_SOUND_MS = 200L
    /** Context kept around a stretch. */
    const val PAD_MS = 300L
    /** Silence between stretches in a packed buffer. */
    const val GAP_MS = 800L
    /** Longest packed buffer (Whisper's window is 30 s). */
    const val MAX_PACK_MS = 28_000L

    /** The louder of the call's two levels (2-means on word levels), if they are ≥ [LOUDER_DB] apart. */
    private fun loudLevel(levels: DoubleArray, weights: List<Double>): Double? {
        if (levels.size < 6) return null
        val m = LevelSpeakers.fit(levels, weights.toDoubleArray()) ?: return null
        return m.loudDb.takeIf { m.separationDb >= LOUDER_DB }
    }

    /** One stretch to read again: [from]..[to] in the recording (padded), placed at [at] in its pack. */
    data class Piece(val from: Long, val to: Long, val at: Long) {
        val length get() = to - from
    }

    fun find(samples: FloatArray, words: List<Transcript.Word>, segments: List<Transcript.Segment>, sampleRate: Int = 16_000): List<LongRange> {
        val perFrame = (sampleRate * FRAME_MS / 1000).toInt()
        val n = samples.size / perFrame
        if (n == 0) return emptyList()
        val db = DoubleArray(n) { f -> rmsDb(samples, f * perFrame, (f + 1) * perFrame) }
        val floor = db.sorted()[(n * 0.1).toInt().coerceAtMost(n - 1)]
        val sound = BooleanArray(n) { db[it] > maxOf(floor + ABOVE_FLOOR_DB, MIN_DB) }

        // Which frames a word covers, and each word's typical level (median of its frames).
        val spans = if (words.isNotEmpty()) words.map { it.startMs to it.endMs } else segments.map { it.startMs to it.endMs }
        val covered = BooleanArray(n)
        val wordDb = DoubleArray(spans.size)
        val wordFrames = spans.map { (a, b) ->
            val from = (a / FRAME_MS).toInt().coerceIn(0, n - 1)
            val to = ((b + FRAME_MS - 1) / FRAME_MS).toInt().coerceIn(from + 1, n)
            from until to
        }
        wordFrames.forEachIndexed { i, fr ->
            for (f in fr) covered[f] = true
            wordDb[i] = fr.map { db[it] }.sorted()[fr.count() / 2]
        }
        // A louder voice over a quieter one's word: only when the call has two clearly
        // different levels (held to the ear), frames at the loud level inside quiet words.
        val loud = loudLevel(wordDb, spans.map { (a, b) -> (b - a).toDouble().coerceAtLeast(40.0) })
        val loudCovered = BooleanArray(n)
        if (loud != null) wordFrames.forEachIndexed { i, fr -> if (wordDb[i] >= loud - 8) for (f in fr) loudCovered[f] = true }
        val missed = BooleanArray(n) { f ->
            sound[f] && (!covered[f] || loud != null && db[f] >= loud - 6 && !loudCovered[f])
        }

        val runs = ArrayList<LongRange>()
        var start = -1
        var last = -1
        var count = 0
        fun close() {
            if (start >= 0 && count * FRAME_MS >= MIN_SOUND_MS) runs += (start * FRAME_MS)..((last + 1) * FRAME_MS)
            start = -1; count = 0
        }
        for (f in 0 until n) {
            if (missed[f]) {
                if (start < 0) start = f
                last = f; count++
            } else if (start >= 0 && f - last > BRIDGE_FRAMES) close()
        }
        close()
        return runs
    }

    /**
     * The stretches padded and packed into buffers of at most [MAX_PACK_MS], [GAP_MS] of
     * silence between them. A stretch longer than a buffer is cut into pieces.
     */
    fun pack(runs: List<LongRange>, totalMs: Long): List<List<Piece>> {
        // Pad, join overlapping, cut long ones.
        val padded = ArrayList<LongRange>()
        for (r in runs) {
            val a = (r.first - PAD_MS).coerceAtLeast(0)
            val b = (r.last + PAD_MS).coerceAtMost(totalMs)
            val prev = padded.lastOrNull()
            if (prev != null && a <= prev.last) padded[padded.lastIndex] = prev.first..maxOf(prev.last, b) else padded += a..b
        }
        val cut = padded.flatMap { r -> (r.first until r.last step MAX_PACK_MS).map { s -> s..minOf(r.last, s + MAX_PACK_MS) } }
        val packs = ArrayList<List<Piece>>()
        var current = ArrayList<Piece>()
        var at = 0L
        for (r in cut) {
            val len = r.last - r.first
            if (current.isNotEmpty() && at + len > MAX_PACK_MS) { packs += current; current = ArrayList(); at = 0 }
            current += Piece(r.first, r.last, at)
            at += len + GAP_MS
        }
        if (current.isNotEmpty()) packs += current
        return packs
    }

    /** The audio of a pack: its pieces one after another, silence between. */
    fun buffer(samples: FloatArray, pack: List<Piece>, sampleRate: Int = 16_000): FloatArray {
        val perMs = sampleRate / 1000
        val last = pack.last()
        val out = FloatArray(((last.at + last.length) * perMs).toInt())
        for (p in pack) {
            val from = (p.from * perMs).toInt().coerceIn(0, samples.size)
            val to = (p.to * perMs).toInt().coerceIn(from, samples.size)
            System.arraycopy(samples, from, out, (p.at * perMs).toInt(), minOf(to - from, out.size - (p.at * perMs).toInt()))
        }
        return out
    }

    /** A time in the pack's audio as a time in the recording; null in the silence between pieces. */
    fun toRecording(pack: List<Piece>, ms: Long): Long? =
        pack.firstOrNull { ms >= it.at && ms <= it.at + it.length }?.let { it.from + (ms - it.at) }

    private fun rmsDb(samples: FloatArray, from: Int, to: Int): Double {
        val a = from.coerceIn(0, samples.size)
        val b = to.coerceIn(a, samples.size)
        if (b - a < 16) return -140.0
        var s = 0.0
        for (i in a until b) s += samples[i].toDouble() * samples[i]
        val rms = sqrt(s / (b - a))
        return if (rms <= 1e-7) -140.0 else 20 * log10(rms)
    }
}
