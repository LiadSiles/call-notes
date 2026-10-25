package com.callnotes.inference.speakers

import com.callnotes.inference.Transcript
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class LevelSpeakersTest {

    /**
     * A fake handset call: [plan] gives (speaker, words) runs; speaker 0 at amplitude
     * [loud], speaker 1 at [quiet]; 300 ms words, 80 ms gaps inside a run, 700 ms between runs.
     */
    private fun call(plan: List<Pair<Int, Int>>, loud: Float = 0.3f, quiet: Float = 0.03f): Pair<FloatArray, List<Pair<Transcript.Word, Int>>> {
        val words = ArrayList<Pair<Transcript.Word, Int>>()
        var t = 200L
        for ((who, n) in plan) {
            repeat(n) {
                // Natural variation: every third word a bit softer.
                words += Transcript.Word(t, t + 300, "w") to who
                t += 380
            }
            t += 620
        }
        val samples = FloatArray(((t + 500) * 16).toInt())
        words.forEachIndexed { i, (w, who) ->
            val amp = (if (who == 0) loud else quiet) * (if (i % 3 == 0) 0.6f else 1f)
            for (k in (w.startMs * 16).toInt() until (w.endMs * 16).toInt()) {
                samples[k] = amp * sin(2 * PI * 220 * k / 16_000).toFloat()
            }
        }
        return samples to words
    }

    /** Speakers from loudness alone (no voice model). */
    private fun byLoudness(samples: FloatArray, words: List<Transcript.Word>) =
        SpeakerDecoder.decode(words, DoubleArray(words.size) { LevelSpeakers.levelDb(samples, words[it]) }, List(words.size) { null })

    @Test fun handsetCallSplitsByLoudness() {
        val plan = listOf(0 to 6, 1 to 5, 0 to 3, 1 to 8, 0 to 4)
        val (samples, labelled) = call(plan)
        val r = byLoudness(samples, labelled.map { it.first })!!
        assertArrayEquals(labelled.map { it.second }.toIntArray(), r.labels)
        val gap = r.level!!.separationDb
        assertTrue("separation $gap", gap > 15)
    }

    @Test fun oneSoftWordInsidePhraseDoesNotFlipSpeaker() {
        val plan = listOf(0 to 8, 1 to 8)
        val (samples, labelled) = call(plan)
        // Make one word in the middle of the loud phrase much softer (a mumbled word).
        val w = labelled[4].first
        for (k in (w.startMs * 16).toInt() until (w.endMs * 16).toInt()) samples[k] *= 0.25f
        val r = byLoudness(samples, labelled.map { it.first })!!
        assertEquals(0, r.labels[4])
    }

    @Test fun similarLevelsAloneDontSplit() {
        val (samples, labelled) = call(listOf(0 to 8, 1 to 8, 0 to 8), loud = 0.3f, quiet = 0.22f)
        assertNull(byLoudness(samples, labelled.map { it.first }))
    }

    @Test fun equaliseLiftsQuietSpeechButNotSilence() {
        val (samples, _) = call(listOf(0 to 4, 1 to 4))
        val eq = LevelSpeakers.equalise(samples)
        assertTrue(eq.all { it in -1f..1f })
        val silence = (100 * 16 until 150 * 16)            // before the first word
        assertTrue(silence.all { eq[it] == 0f })
    }
}
