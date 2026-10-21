package com.callnotes.audio

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Brings a chunk to a steady speech level before it goes to a model. Call recordings
 * vary a lot (far side through the speaker, VOICE_RECOGNITION without AGC), and quiet
 * input makes speech models guess. Works on a copy; recordings are never modified.
 */
object AudioLevel {

    /** Target RMS of the speech frames, ≈ −20 dBFS. */
    private const val TARGET_RMS = 3_300.0
    /** Never push peaks above ≈ −1 dBFS. */
    private const val PEAK_LIMIT = 29_000.0
    private const val MAX_GAIN = 12.0
    private const val FRAME = 1_600 // 100 ms at 16 kHz
    /** Frames quieter than this (≈ −49 dBFS) are pauses; they don't set the level. */
    private const val PAUSE_RMS = 120.0

    fun normalize(x: ShortArray): ShortArray {
        if (x.isEmpty()) return x
        val mean = x.sumOf { it.toLong() }.toDouble() / x.size // remove DC offset

        // Level of the frames that carry sound, so long pauses don't inflate the gain.
        var sum = 0.0
        var n = 0L
        var i = 0
        while (i < x.size) {
            val end = minOf(i + FRAME, x.size)
            var fs = 0.0
            for (k in i until end) { val v = x[k] - mean; fs += v * v }
            if (sqrt(fs / (end - i)) >= PAUSE_RMS) { sum += fs; n += end - i }
            i = end
        }
        if (n == 0L) return x // silence: leave it

        val rms = sqrt(sum / n)
        var peak = 0.0
        for (v in x) peak = maxOf(peak, abs(v - mean))
        val gain = minOf(TARGET_RMS / rms, PEAK_LIMIT / peak.coerceAtLeast(1.0), MAX_GAIN)
        return ShortArray(x.size) { k ->
            ((x[k] - mean) * gain).toInt().coerceIn(-32_768, 32_767).toShort()
        }
    }
}
