package com.callnotes.inference.whisper

import com.callnotes.inference.Transcript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class MissedSpeechTest {

    /** [seconds] of faint noise; [voices]: (from s, to s, amplitude). */
    private fun audio(seconds: Int, voices: List<Triple<Double, Double, Double>>): FloatArray {
        val rnd = java.util.Random(1)
        return FloatArray(seconds * 16_000) { i ->
            val t = i / 16_000.0
            var x = rnd.nextGaussian() * 0.0005
            for ((a, b, amp) in voices) if (t >= a && t < b) x += amp * sin(2 * Math.PI * 220 * t)
            x.toFloat()
        }
    }

    private fun word(a: Double, b: Double) = Transcript.Word((a * 1000).toLong(), (b * 1000).toLong(), "w")

    @Test fun shortAnswerWithoutWordsIsFound() {
        // A 0.4 s "כן" at 3 s that Whisper skipped; everything else has words.
        val samples = audio(8, listOf(Triple(1.0, 2.5, 0.03), Triple(3.0, 3.4, 0.03), Triple(4.0, 6.0, 0.03)))
        val words = listOf(word(1.0, 2.5), word(4.0, 6.0))
        val runs = MissedSpeech.find(samples, words, emptyList())
        assertEquals(1, runs.size)
        assertTrue(runs[0].first in 2_900L..3_100L && runs[0].last in 3_300L..3_500L)
    }

    @Test fun loudWordOverAQuietSentenceIsFound() {
        // Held to the ear: the other side quiet (0.003), the owner loud (0.1). The owner's
        // "כן" at 4.8–5.1 s falls inside one of the other side's words (4.6–5.4 s): Whisper
        // heard only that word.
        val quiet = (0 until 10).map { k -> Triple(1.0 + k * 0.9, 1.0 + k * 0.9 + 0.8, 0.003) }
        val loud = listOf(11.0 to 12.0, 12.2 to 13.2, 13.5 to 14.5, 14.7 to 15.7).map { Triple(it.first, it.second, 0.1) }
        val samples = audio(17, quiet + loud + Triple(4.8, 5.1, 0.1))
        val words = (quiet + loud).map { word(it.first, it.second) }
        val runs = MissedSpeech.find(samples, words, emptyList())
        assertTrue(runs.toString(), runs.any { it.first in 4_700L..4_900L && it.last in 5_000L..5_200L })
        assertTrue(runs.none { it.first >= 10_000L }) // the owner's own words are not looked at again
    }

    @Test fun everythingCoveredGivesNothing() {
        val samples = audio(6, listOf(Triple(1.0, 4.0, 0.03)))
        assertTrue(MissedSpeech.find(samples, listOf(word(1.0, 4.0)), emptyList()).isEmpty())
        assertTrue(MissedSpeech.find(audio(6, emptyList()), emptyList(), emptyList()).isEmpty())
    }

    @Test fun piecesArePackedAndMappedBack() {
        val runs = listOf(3_000L..3_400L, 10_000L..10_500L, 50_000L..80_000L)
        val packs = MissedSpeech.pack(runs, 90_000)
        assertTrue(packs.all { p -> p.last().at + p.last().length <= MissedSpeech.MAX_PACK_MS })
        // The two short ones share the first pack.
        assertEquals(2700L, packs[0][0].from)
        assertEquals(10_000L - MissedSpeech.PAD_MS, packs[0][1].from)
        // A time inside the second piece maps back into the recording; the silence between doesn't.
        val p1 = packs[0][1]
        assertEquals(p1.from + 100, MissedSpeech.toRecording(packs[0], p1.at + 100))
        assertNull(MissedSpeech.toRecording(packs[0], packs[0][0].at + packs[0][0].length + 10))
        // The long one is cut into ≤ 28 s pieces.
        assertTrue(packs.flatten().filter { it.from >= 49_000 }.size >= 2)
    }

    @Test fun bufferHoldsThePiecesInOrder() {
        val samples = FloatArray(10 * 16_000) { (it / 16_000).toFloat() } // value = second
        val pack = listOf(MissedSpeech.Piece(2_000, 2_500, 0), MissedSpeech.Piece(7_000, 7_500, 1_300))
        val buf = MissedSpeech.buffer(samples, pack)
        assertEquals(2f, buf[100], 0f)
        assertEquals(0f, buf[(900 * 16)], 0f)       // silence between
        assertEquals(7f, buf[(1_400 * 16)], 0f)
    }
}
