package com.callnotes.inference.speakers

import com.callnotes.inference.Transcript
import kotlin.math.sqrt

/**
 * A voice fingerprint for every word. Fingerprints need ≥ 1 s of audio, and grouping words
 * at pauses (as [VoiceClusters] does) makes windows that span both speakers whenever one
 * answers without a pause — the whole window then gets one speaker. Instead the speech is
 * covered by short overlapping windows ([WINDOW_MS], every [HOP_MS]); each word takes the
 * average of the windows centred on it, so a change of speaker shows within a word or two.
 * Pure (unit-tested); the fingerprints come from [VoiceEmbedder].
 */
object VoiceTrack {

    const val WINDOW_MS = 1_500L
    const val HOP_MS = 750L
    /** Words closer than this form one stretch of speech. */
    private const val REGION_GAP_MS = 1_000L
    /** A word uses the windows whose centre is within this of its middle. */
    private const val REACH_MS = 500L

    /** The windows to fingerprint: over each stretch of speech, every [HOP_MS]. */
    fun windows(words: List<Transcript.Word>): List<LongRange> {
        if (words.isEmpty()) return emptyList()
        val out = ArrayList<LongRange>()
        var start = words[0].startMs
        var end = words[0].endMs
        fun region(a: Long, b: Long) {
            if (b - a <= WINDOW_MS) {
                val mid = (a + b) / 2
                val s = (mid - WINDOW_MS / 2).coerceAtLeast(0)
                out += s..(s + WINDOW_MS)
                return
            }
            var t = a
            while (t + WINDOW_MS < b) { out += t..(t + WINDOW_MS); t += HOP_MS }
            out += (b - WINDOW_MS)..b
        }
        for (w in words.drop(1)) {
            if (w.startMs - end > REGION_GAP_MS) { region(start, end); start = w.startMs }
            end = maxOf(end, w.endMs)
        }
        region(start, end)
        return out
    }

    /**
     * Per word: the normalised mean of the fingerprints of [windows] centred near it (the
     * nearest one within a second if none is close), or null without any.
     */
    fun perWord(words: List<Transcript.Word>, windows: List<LongRange>, embeddings: List<FloatArray?>): List<FloatArray?> {
        val centres = windows.map { (it.first + it.last) / 2 }
        return words.map { w ->
            val mid = (w.startMs + maxOf(w.endMs, w.startMs + 1)) / 2
            var picked = windows.indices.filter { embeddings[it] != null && kotlin.math.abs(centres[it] - mid) <= REACH_MS }
            if (picked.isEmpty()) {
                picked = windows.indices.filter { embeddings[it] != null }
                    .minByOrNull { kotlin.math.abs(centres[it] - mid) }
                    ?.takeIf { kotlin.math.abs(centres[it] - mid) <= 1_000 }?.let { listOf(it) } ?: emptyList()
            }
            if (picked.isEmpty()) null else unit(mean(picked.map { embeddings[it]!! }))
        }
    }

    internal fun unit(v: FloatArray): FloatArray {
        var n = 0.0
        for (x in v) n += x * x
        val k = if (n > 0) (1 / sqrt(n)).toFloat() else 0f
        return FloatArray(v.size) { v[it] * k }
    }

    internal fun mean(vs: List<FloatArray>): FloatArray {
        val out = FloatArray(vs[0].size)
        for (v in vs) { val u = unit(v); for (i in out.indices) out[i] += u[i] }
        return out
    }
}
