package com.callnotes.inference.speakers

import com.callnotes.inference.Transcript
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Loudness as evidence of who is speaking, for calls held to the ear: the owner speaks a
 * few centimetres from the microphone, the other side only leaks out of the earpiece, so
 * the two voices arrive ~10–30 dB apart. Voice fingerprints struggle there (they mostly hear
 * one voice); loudness doesn't.
 *
 * Each Whisper word gets its level in dBFS; [fit] finds the two levels (weighted 2-means).
 * [SpeakerDecoder] weighs this against the voices; [switchCost] is the pause-aware price of
 * a speaker change both use. Pure (unit-tested).
 */
object LevelSpeakers {

    /** Below this the clusters are just one voice getting louder and softer. */
    const val MIN_SEPARATION_DB = 7.0
    private const val MIN_WORDS = 12

    /** RMS level of a word in dBFS, over its span widened by 40 ms each side. */
    fun levelDb(samples: FloatArray, w: Transcript.Word, sampleRate: Int = 16_000): Double {
        val perMs = sampleRate / 1000
        val from = ((w.startMs - 40) * perMs).toInt().coerceIn(0, samples.size)
        val to = ((max(w.endMs, w.startMs + 80) + 40) * perMs).toInt().coerceIn(from, samples.size)
        if (to - from < perMs * 20) return -100.0
        var sum = 0.0
        for (i in from until to) sum += samples[i].toDouble() * samples[i]
        val rms = sqrt(sum / (to - from))
        return if (rms <= 1e-6) -100.0 else 20 * log10(rms)
    }

    /** Two loudness levels (weighted 2-means in dB) and the spread around them. */
    data class Model(val loudDb: Double, val quietDb: Double, val sd: Double) {
        val separationDb get() = loudDb - quietDb
    }

    /** Fits [Model] to the measurable words; null if there are too few. */
    fun fit(levels: DoubleArray, weights: DoubleArray): Model? {
        val usable = levels.indices.filter { levels[it] > -80 }
        if (usable.size < MIN_WORDS) return null
        var hi = usable.maxOf { levels[it] }
        var lo = usable.minOf { levels[it] }
        repeat(30) {
            var sh = 0.0; var wh = 0.0; var sl = 0.0; var wl = 0.0
            for (i in usable) {
                if (abs(levels[i] - hi) <= abs(levels[i] - lo)) { sh += levels[i] * weights[i]; wh += weights[i] }
                else { sl += levels[i] * weights[i]; wl += weights[i] }
            }
            if (wh > 0) hi = sh / wh
            if (wl > 0) lo = sl / wl
        }
        // Pooled spread for the emission model (floor keeps one odd word from dominating).
        var varSum = 0.0; var wSum = 0.0
        for (i in usable) {
            val d = levels[i] - if (abs(levels[i] - hi) <= abs(levels[i] - lo)) hi else lo
            varSum += d * d * weights[i]; wSum += weights[i]
        }
        return Model(hi, lo, max(3.0, sqrt(varSum / wSum)))
    }

    /** Log-odds of a speaker change into a word after a pause of [gapMs]: cheap at a pause, dear mid-phrase. */
    fun switchCost(gapMs: Long): Double {
        val p = when {
            gapMs < 250 -> 0.03
            gapMs < 700 -> 0.15
            gapMs < 1500 -> 0.3
            else -> 0.45
        }
        return ln(p) - ln(1 - p)
    }

    /** Word labels as speaker turns, for [SpeakerAligner.align]. */
    fun turns(words: List<Transcript.Word>, labels: IntArray): List<SpeakerAligner.Turn> =
        words.indices.map { SpeakerAligner.Turn(words[it].startMs, max(words[it].endMs, words[it].startMs + 1), labels[it]) }

    /**
     * Evens out loudness over time (0.5 s windows, gain ≤ 20×, pauses left alone), so the
     * quiet side isn't ignored by the voice-embedding diarizer. A copy; the recording is
     * never changed.
     */
    fun equalise(x: FloatArray, sampleRate: Int = 16_000): FloatArray {
        val win = sampleRate / 2
        val nWin = (x.size + win - 1) / win
        if (nWin == 0) return x
        val gains = DoubleArray(nWin) { w ->
            val from = w * win; val to = minOf(x.size, from + win)
            var s = 0.0
            for (i in from until to) s += x[i].toDouble() * x[i]
            val rms = sqrt(s / max(1, to - from))
            if (rms < 0.002) 1.0 else (0.1 / rms).coerceIn(0.5, 20.0) // pauses (≈ −54 dBFS) untouched
        }
        val out = FloatArray(x.size)
        for (i in x.indices) {
            // Linear interpolation between window centres avoids clicks.
            val pos = (i - win / 2).toDouble() / win
            val a = pos.toInt().coerceIn(0, nWin - 1)
            val b = (a + 1).coerceAtMost(nWin - 1)
            val t = (pos - a).coerceIn(0.0, 1.0)
            val g = gains[a] * (1 - t) + gains[b] * t
            out[i] = (x[i] * g).toFloat().coerceIn(-1f, 1f)
        }
        return out
    }
}
