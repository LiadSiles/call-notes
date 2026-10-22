package com.callnotes.inference.speakers

import com.callnotes.inference.Transcript
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Who-said-what by voice, for calls where loudness doesn't separate the sides
 * (speakerphone, VoIP). Replaces sherpa-onnx's diarization pipeline, which calls
 * `exit(-1)` on edge cases (e.g. a segment too short to embed) and killed the app.
 *
 * Whisper's words are grouped into short windows (split at pauses, ≤ 3 s); each window
 * long enough gets a voice fingerprint; two clusters by cosine similarity; a two-state
 * Viterbi pass smooths the sequence (short windows inherit from their neighbours).
 * Only the fingerprint model runs natively, and only on audio we've checked is long enough.
 * Pure logic here (unit-tested); the fingerprints come from [VoiceEmbedder].
 */
object VoiceClusters {

    data class Window(val firstWord: Int, val lastWord: Int, val startMs: Long, val endMs: Long, val gapBeforeMs: Long) {
        val durationMs get() = endMs - startMs
    }

    data class Result(
        /** Speaker 0/1 per window. */
        val labels: IntArray,
        val centroids: Map<Int, FloatArray>,
        /** Cosine between the two voices. */
        val similarity: Float,
        val shareOf1: Double,
    )

    private const val PAUSE_MS = 400L
    private const val MAX_WINDOW_MS = 3_000L
    /** Shorter windows aren't fingerprinted (too little audio to trust). */
    const val MIN_EMBED_MS = 1_000L
    const val SAME_VOICE = 0.75f
    private const val MIN_SHARE = 0.05

    fun windows(words: List<Transcript.Word>): List<Window> {
        val out = ArrayList<Window>()
        var first = 0
        for (i in 1..words.size) {
            val end = i == words.size ||
                words[i].startMs - words[i - 1].endMs > PAUSE_MS ||
                words[i].endMs - words[first].startMs > MAX_WINDOW_MS
            if (end) {
                val gap = if (first == 0) 0 else words[first].startMs - words[first - 1].endMs
                out += Window(first, i - 1, words[first].startMs, maxOf(words[i - 1].endMs, words[first].startMs + 1), gap)
                first = i
            }
        }
        return if (words.isEmpty()) emptyList() else out
    }

    /**
     * Clusters windows into two voices. [embeddings] has one entry per window, null for
     * windows too short to fingerprint. Returns null if there aren't two distinct voices.
     */
    fun cluster(windows: List<Window>, embeddings: List<FloatArray?>): Result? {
        val idx = embeddings.indices.filter { embeddings[it] != null }
        if (idx.size < 4) return null
        val e = embeddings.map { it?.let(::unit) }
        val w = windows.map { it.durationMs.toDouble() }

        // Seeds: the longest window, and the window least like it.
        val a = idx.maxBy { w[it] }
        val b = idx.minBy { dot(e[it]!!, e[a]!!) }
        var c = arrayOf(e[a]!!, e[b]!!)
        val assign = IntArray(embeddings.size)
        repeat(15) {
            for (i in idx) assign[i] = if (dot(e[i]!!, c[0]) >= dot(e[i]!!, c[1])) 0 else 1
            c = Array(2) { s ->
                val members = idx.filter { assign[it] == s }
                if (members.isEmpty()) c[s] else mean(members.map { e[it]!! }, members.map { w[it] })
            }
        }
        val similarity = dot(c[0], c[1])
        if (similarity >= SAME_VOICE) return null

        val labels = viterbi(windows, e, c)
        var t1 = 0.0; var tot = 0.0
        for (i in windows.indices) { tot += w[i]; if (labels[i] == 1) t1 += w[i] }
        val share = t1 / tot
        if (share < MIN_SHARE || share > 1 - MIN_SHARE) return null
        return Result(labels, mapOf(0 to c[0], 1 to c[1]), similarity, share)
    }

    private fun viterbi(windows: List<Window>, e: List<FloatArray?>, c: Array<FloatArray>): IntArray =
        TwoSpeakerViterbi.decode(
            windows.size,
            // Evidence is the margin between the two voices, scaled so a clear window
            // outweighs one switch; a window too short to fingerprint follows its neighbours.
            emit = { i, s -> e[i]?.let { v -> 8.0 * (dot(v, c[s]) - dot(v, c[1 - s])) } ?: 0.0 },
            switchCost = { i -> if (windows[i].gapBeforeMs > PAUSE_MS) ln(0.3 / 0.7) else ln(0.08 / 0.92) },
        )

    fun turns(windows: List<Window>, labels: IntArray): List<SpeakerAligner.Turn> =
        windows.indices.map { SpeakerAligner.Turn(windows[it].startMs, windows[it].endMs, labels[it]) }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    private fun unit(v: FloatArray): FloatArray {
        var n = 0.0
        for (x in v) n += x * x
        val k = if (n > 0) (1 / sqrt(n)).toFloat() else 0f
        return FloatArray(v.size) { v[it] * k }
    }

    private fun mean(vs: List<FloatArray>, ws: List<Double>): FloatArray {
        val out = FloatArray(vs[0].size)
        for ((v, wt) in vs.zip(ws)) for (i in out.indices) out[i] += (v[i] * wt).toFloat()
        return unit(out)
    }
}
