package com.callnotes.inference.whisper

import com.callnotes.inference.Transcript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class SpeechGapsTest {

    /** [seconds] of faint noise with louder "speech" in [loud] (seconds). */
    private fun audio(seconds: Int, loud: List<ClosedFloatingPointRange<Double>>): FloatArray {
        val rnd = java.util.Random(1)
        return FloatArray(seconds * 16_000) { i ->
            val t = i / 16_000.0
            val noise = (rnd.nextGaussian() * 0.0005).toFloat()
            if (loud.any { t in it }) noise + (0.03 * sin(2 * Math.PI * 220 * t)).toFloat() else noise
        }
    }

    @Test fun speechWithoutWordsIsFound() {
        val samples = audio(20, listOf(2.0..5.0, 10.0..12.0))
        val words = listOf(Transcript.Word(10_000, 11_000, "שלום"), Transcript.Word(11_000, 12_000, "דני"))
        val gaps = SpeechGaps.find(samples, words, emptyList())
        assertEquals(1, gaps.size)
        assertTrue(gaps[0].first in 1_400L..2_000L)
        assertTrue(gaps[0].last in 5_000L..5_600L)
    }

    @Test fun everythingCoveredOrSilentGivesNothing() {
        val samples = audio(10, listOf(3.0..6.0))
        val words = listOf(Transcript.Word(3_000, 6_000, "הכל"))
        assertTrue(SpeechGaps.find(samples, words, emptyList()).isEmpty())
        assertTrue(SpeechGaps.find(audio(10, emptyList()), emptyList(), emptyList()).isEmpty())
    }

    @Test fun shortBlipsAreIgnoredAndLongStretchesSplit() {
        assertTrue(SpeechGaps.find(audio(10, listOf(4.0..4.5)), emptyList(), emptyList()).isEmpty())
        val long = SpeechGaps.find(audio(80, listOf(5.0..70.0)), emptyList(), emptyList())
        assertTrue(long.size >= 3)
        assertTrue(long.all { it.last - it.first <= SpeechGaps.MAX_PIECE_MS })
    }
}
