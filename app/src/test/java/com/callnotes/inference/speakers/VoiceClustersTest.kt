package com.callnotes.inference.speakers

import com.callnotes.inference.Transcript
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

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

    private val rnd = Random(7)
    private fun voice(base: FloatArray) = FloatArray(base.size) { base[it] + (rnd.nextFloat() - 0.5f) * 0.3f }
    private val a = FloatArray(16) { if (it < 8) 1f else 0f }
    private val b = FloatArray(16) { if (it < 8) 0f else 1f }

    @Test fun windowsSplitAtPausesAndLength() {
        val w = VoiceClusters.windows(words(4 to 0L, 3 to 800L, 10 to 800L))
        // 4 words (1.9 s) | 3 words | 10 words split by the 3 s cap
        assertEquals(listOf(0, 4, 7, 13), w.map { it.firstWord })
        assertEquals(800L + 100, w[1].gapBeforeMs)
    }

    @Test fun twoVoicesAreSeparatedAndShortWindowFollowsNeighbours() {
        val ws = VoiceClusters.windows(words(4 to 0L, 4 to 800L, 4 to 800L, 1 to 800L, 4 to 800L, 4 to 800L))
        val truth = intArrayOf(0, 1, 0, 0, 0, 1)
        // Window 3 is one short word: no fingerprint.
        val emb = ws.indices.map { i -> if (ws[i].durationMs < VoiceClusters.MIN_EMBED_MS) null else voice(if (truth[i] == 0) a else b) }
        val r = VoiceClusters.cluster(ws, emb)!!
        val labels = if (r.labels[0] == 0) r.labels else r.labels.map { 1 - it }.toIntArray()
        assertArrayEquals(truth, labels)
    }

    @Test fun oneVoiceGivesNoSplit() {
        val ws = VoiceClusters.windows(words(4 to 0L, 4 to 800L, 4 to 800L, 4 to 800L, 4 to 800L))
        assertNull(VoiceClusters.cluster(ws, ws.map { voice(a) }))
    }
}
