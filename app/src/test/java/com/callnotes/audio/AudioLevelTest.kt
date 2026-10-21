package com.callnotes.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class AudioLevelTest {

    private fun tone(amplitude: Double, samples: Int = 16_000) =
        ShortArray(samples) { (amplitude * sin(2 * PI * 300 * it / 16_000.0)).toInt().toShort() }

    private fun rms(x: ShortArray) = sqrt(x.sumOf { it.toDouble() * it } / x.size)

    @Test fun quietSpeechIsRaisedTowardsTarget() {
        val out = AudioLevel.normalize(tone(500.0))
        assertTrue("rms ${rms(out)}", rms(out) in 3_000.0..3_600.0)
    }

    @Test fun peaksAreLimited() {
        val out = AudioLevel.normalize(tone(30_000.0))
        assertTrue(out.maxOf { abs(it.toInt()) } <= 29_100)
    }

    @Test fun silenceIsUntouched() {
        val x = ShortArray(16_000) { (it % 3 - 1).toShort() }
        assertArrayEquals(x, AudioLevel.normalize(x))
    }

    @Test fun pausesDoNotInflateGain() {
        // 1 s of speech then 9 s of near silence: level comes from the speech only.
        val x = tone(1_000.0) + ShortArray(144_000)
        val out = AudioLevel.normalize(x)
        val speech = out.copyOfRange(0, 16_000)
        assertTrue("rms ${rms(speech)}", rms(speech) in 3_000.0..3_600.0)
        assertEquals(0, out.copyOfRange(20_000, 160_000).maxOf { abs(it.toInt()) })
    }
}
