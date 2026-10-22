package com.callnotes.inference.speakers

import com.callnotes.inference.Transcript
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Speaker separation by loudness, for calls held to the ear: the owner speaks a few
 * centimetres from the microphone, the other side only leaks out of the earpiece, so the
 * two voices arrive ~10–30 dB apart. Voice-embedding diarization fails there (it mostly
 * hears one voice); loudness doesn't.
 *
 * Each Whisper word gets its level in dBFS; two clusters (weighted 2-means); a two-state
 * Viterbi pass smooths the labels so a speaker change is cheap at a pause and expensive
 * inside a phrase. Returns null when the levels don't separate (speakerphone, VoIP) —
 * the caller then uses voice embeddings. Pure (unit-tested).
 */
object LevelSpeakers {

    data class Result(
        /** Per word: 0 = louder (normally the owner), 1 = quieter (normally the other side). */
        val labels: IntArray,
        val loudDb: Double,
        val quietDb: Double,
        /** Share of speech time in the quieter cluster. */
        val quietShare: Double,
    ) {
        val separationDb get() = loudDb - quietDb
    }

    /** Below this the clusters are just one voice getting louder and softer. */
    const val MIN_SEPARATION_DB = 7.0
    /** Each side must have at least this share of the speech time. */
    const val MIN_SHARE = 0.06
    private const val MIN_WORDS = 12

    fun analyse(samples: FloatArray, words: List<Transcript.Word>, sampleRate: Int = 16_000): Result? {
        if (words.size < MIN_WORDS) return null
        val levels = DoubleArray(words.size) { levelDb(samples, words[it], sampleRate) }
        val weights = DoubleArray(words.size) { max(40.0, (words[it].endMs - words[it].startMs).toDouble()) }
        val gaps = LongArray(words.size) { i -> if (i == 0) 0 else words[i].startMs - words[i - 1].endMs }
        return split(levels, weights, gaps)
    }

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

    fun split(levels: DoubleArray, weights: DoubleArray, gaps: LongArray): Result? {
        val n = levels.size
        val usable = levels.indices.filter { levels[it] > -80 }
        if (usable.size < MIN_WORDS) return null

        // Weighted 2-means in dB.
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
        if (hi - lo < MIN_SEPARATION_DB) return null

        // Pooled spread for the emission model (floor keeps one odd word from dominating).
        var varSum = 0.0; var wSum = 0.0
        for (i in usable) {
            val d = levels[i] - if (abs(levels[i] - hi) <= abs(levels[i] - lo)) hi else lo
            varSum += d * d * weights[i]; wSum += weights[i]
        }
        val sd = max(3.0, sqrt(varSum / wSum))

        val labels = viterbi(levels, gaps, doubleArrayOf(hi, lo), sd)
        var quiet = 0.0; var total = 0.0
        for (i in 0 until n) { total += weights[i]; if (labels[i] == 1) quiet += weights[i] }
        val share = quiet / total
        if (share < MIN_SHARE || share > 1 - MIN_SHARE) return null
        return Result(labels, hi, lo, share)
    }

    /** Most likely speaker sequence; switching costs less after a longer pause. */
    private fun viterbi(levels: DoubleArray, gaps: LongArray, means: DoubleArray, sd: Double): IntArray =
        TwoSpeakerViterbi.decode(
            levels.size,
            emit = { i, s ->
                if (levels[i] <= -80) 0.0 // unmeasurable word: no evidence either way
                else { val z = (levels[i] - means[s]) / sd; -0.5 * z * z }
            },
            switchCost = { i ->
                val p = when {
                    gaps[i] < 250 -> 0.03
                    gaps[i] < 700 -> 0.15
                    gaps[i] < 1500 -> 0.3
                    else -> 0.45
                }
                ln(p) - ln(1 - p)
            },
        )

    /** Word labels as speaker turns, for [SpeakerAligner.align]. */
    fun turns(words: List<Transcript.Word>, labels: IntArray): List<SpeakerAligner.Turn> =
        words.indices.map { SpeakerAligner.Turn(words[it].startMs, max(words[it].endMs, words[it].startMs + 1), labels[it]) }

    /** Audio of [speaker]'s words (up to [maxSeconds]), for a voice fingerprint. */
    fun samplesOf(samples: FloatArray, words: List<Transcript.Word>, labels: IntArray, speaker: Int, maxSeconds: Int = 20): FloatArray {
        val out = FloatArray(maxSeconds * 16_000)
        var size = 0
        for (i in words.indices) {
            if (labels[i] != speaker) continue
            val from = (words[i].startMs * 16).toInt().coerceIn(0, samples.size)
            val to = (words[i].endMs * 16).toInt().coerceIn(from, samples.size)
            val n = minOf(to - from, out.size - size)
            System.arraycopy(samples, from, out, size, n)
            size += n
            if (size == out.size) break
        }
        return out.copyOf(size)
    }

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
