package com.callnotes.audio

import java.io.File
import kotlin.math.sqrt

/**
 * Splits a working-format WAV into chunks the model accepts (Gemma 4 audio: ~30 s per
 * clip). 28 s windows, 3 s overlap; each cut is moved to the quietest 100 ms in the
 * window's last 4 s so words aren't split. Overlaps are reconciled later by
 * [com.callnotes.inference.TranscriptMerger].
 */
object Chunker {

    data class Chunk(val startSample: Long, val endSample: Long, val silent: Boolean) {
        val startMs get() = startSample * 1000 / Wav.SAMPLE_RATE
        val endMs get() = endSample * 1000 / Wav.SAMPLE_RATE
    }

    private const val SR = Wav.SAMPLE_RATE
    private const val WINDOW = 28L * SR
    private const val OVERLAP = 3L * SR
    private const val SEARCH = 4L * SR
    private const val FRAME = SR / 10          // 100 ms
    private const val SILENT_RMS = 120.0       // ≈ −49 dBFS

    fun plan(file: File, info: Wav.Info): List<Chunk> {
        val total = info.sampleCount
        if (total <= 0) return emptyList()
        val chunks = ArrayList<Chunk>()
        var start = 0L
        while (start < total) {
            var end = (start + WINDOW).coerceAtMost(total)
            val samples = Wav.readSamples(file, info, start, (end - start).toInt())
            if (end < total) end = start + quietestCut(samples)
            val silent = rms(samples, 0, (end - start).toInt()) < SILENT_RMS
            chunks += Chunk(start, end, silent)
            if (end >= total) break
            start = (end - OVERLAP).coerceAtLeast(start + FRAME)
        }
        return chunks
    }

    /** Offset (in samples) of the quietest frame centre within the last [SEARCH] of [x]. */
    private fun quietestCut(x: ShortArray): Long {
        val from = (x.size - SEARCH).toInt().coerceAtLeast(0)
        var best = x.size.toLong()
        var bestRms = Double.MAX_VALUE
        var i = from
        while (i + FRAME <= x.size) {
            val r = rms(x, i, FRAME)
            if (r < bestRms) { bestRms = r; best = (i + FRAME / 2).toLong() }
            i += FRAME / 2
        }
        return best
    }

    private fun rms(x: ShortArray, from: Int, len: Int): Double {
        if (len <= 0) return 0.0
        var sum = 0.0
        val to = (from + len).coerceAtMost(x.size)
        for (k in from until to) { val v = x[k].toDouble(); sum += v * v }
        return sqrt(sum / (to - from).coerceAtLeast(1))
    }
}
