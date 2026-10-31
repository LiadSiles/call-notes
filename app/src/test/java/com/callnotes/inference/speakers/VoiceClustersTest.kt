package com.callnotes.inference.speakers

import com.callnotes.inference.Transcript
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceClustersTest {

    private fun words(vararg runs: Pair<Int, Long>): List<Transcript.Word> {
        // runs: (word count, pause before the run in ms); words 400 ms with 100 ms gaps.
        val out = ArrayList<Transcript.Word>()
        var t = 0L
        for ((n, pause) in runs) {
            t += pause
            repeat(n) { out += Transcript.Word(t, t + 400, "w"); t += 500 }
        }
        return out
    }

    @Test fun windowsSplitAtPausesAndLength() {
        val w = VoiceClusters.windows(words(4 to 0L, 3 to 800L, 10 to 800L))
        // 4 words (1.9 s) | 3 words | 10 words split by the 3 s cap
        assertEquals(listOf(0, 4, 7, 13), w.map { it.firstWord })
        assertEquals(800L + 100, w[1].gapBeforeMs)
    }
}
