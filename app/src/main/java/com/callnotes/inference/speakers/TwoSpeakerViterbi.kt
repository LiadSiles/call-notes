package com.callnotes.inference.speakers

/**
 * Most likely sequence of two speakers (0/1) over [n] steps — the smoothing step shared by
 * [LevelSpeakers] (per word) and [VoiceClusters] (per window).
 *
 * [emit] is the log-evidence for speaker s at step i (0.0 = no evidence either way).
 * [switchCost] is the log-odds of changing speaker *into* step i (negative: switching is
 * penalised), so a change is cheap at a long pause and expensive mid-phrase.
 */
internal object TwoSpeakerViterbi {

    fun decode(n: Int, emit: (i: Int, s: Int) -> Double, switchCost: (i: Int) -> Double): IntArray {
        if (n == 0) return IntArray(0)
        val score = Array(n) { DoubleArray(2) }
        val from = Array(n) { IntArray(2) }
        for (s in 0..1) score[0][s] = emit(0, s)
        for (i in 1 until n) {
            val sw = switchCost(i)
            for (s in 0..1) {
                val stay = score[i - 1][s]
                val move = score[i - 1][1 - s] + sw
                if (stay >= move) { score[i][s] = stay + emit(i, s); from[i][s] = s }
                else { score[i][s] = move + emit(i, s); from[i][s] = 1 - s }
            }
        }
        val out = IntArray(n)
        out[n - 1] = if (score[n - 1][0] >= score[n - 1][1]) 0 else 1
        for (i in n - 1 downTo 1) out[i - 1] = from[i][out[i]]
        return out
    }
}
