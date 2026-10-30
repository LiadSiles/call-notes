package com.callnotes.inference.speakers

import com.callnotes.inference.Transcript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundSpeechTest {

    /** A voice: a unit vector on its own axis, slightly varied per window. */
    private fun voice(axis: Int, k: Int) = FloatArray(8) { i -> if (i == axis) 1f else 0.05f * ((i + k) % 3) }

    /** One window per entry: (side, level dB, voice axis or -1 = too short, start s). */
    private data class W(val side: Int, val db: Double, val axis: Int, val startS: Double, val lenS: Double = 2.0)

    private fun run(ws: List<W>, ownerSide: Int? = null, ownerVoice: FloatArray? = null): BackgroundSpeech.Result {
        val windows = ws.mapIndexed { i, w ->
            VoiceClusters.Window(i, i, (w.startS * 1000).toLong(), ((w.startS + w.lenS) * 1000).toLong())
        }
        return BackgroundSpeech.find(
            windows,
            IntArray(ws.size) { ws[it].side },
            DoubleArray(ws.size) { ws[it].db },
            ws.mapIndexed { i, w -> if (w.axis < 0) null else voice(w.axis, i) },
            ownerSide, ownerVoice,
        )
    }

    /** A conversation: owner (axis 0, side 0, −20 dB) and the other side (axis 1, side 1, −34 dB) taking turns. */
    private fun call(turns: Int = 20): MutableList<W> {
        val out = mutableListOf<W>()
        var t = 0.0
        repeat(turns) { k ->
            out += if (k % 2 == 0) W(0, -20.0, 0, t) else W(1, -34.0, 1, t)
            t += 2.5
        }
        return out
    }

    @Test fun quietStrangerOnTheOwnersSideIsSetAside() {
        val ws = call()
        // Someone in the owner's room, 14 dB below the owner, never answered (followed by a long pause).
        listOf(60.0, 70.0, 80.0).forEach { ws += W(0, -34.0, 2, it) }
        val r = run(ws)
        assertEquals(3, r.count)
        assertTrue(r.background.takeLast(3).all { it })
        assertFalse(r.background.take(20).any { it })
    }

    @Test fun twoPeopleOnTheOtherSideBothStay() {
        val ws = mutableListOf<W>()
        var t = 0.0
        repeat(24) { k ->
            ws += when (k % 3) {
                0 -> W(0, -20.0, 0, t)
                1 -> W(1, -34.0, 1, t)  // first person at the other end
                else -> W(1, -35.0, 3, t) // second person there, same level
            }
            t += 2.5
        }
        val r = run(ws)
        assertEquals(0, r.count)
    }

    @Test fun quietVoiceTheOtherSideAnswersStays() {
        val ws = mutableListOf<W>()
        var t = 0.0
        repeat(10) {
            ws += W(0, -20.0, 0, t); t += 2.5
            ws += W(1, -34.0, 1, t); t += 2.5
            // Someone across the owner's room joins in, and the other side replies right after.
            ws += W(0, -33.0, 2, t); t += 2.5
            ws += W(1, -34.0, 1, t); t += 2.5
        }
        val r = run(ws)
        assertEquals(0, r.count)
        assertEquals(1, r.keptAnswered)
    }

    @Test fun ownerSpeakingSoftlyIsNotBackground() {
        val ws = call()
        listOf(60.0, 70.0).forEach { ws += W(0, -33.0, 0, it) } // the owner, quieter
        assertEquals(0, run(ws).count)
    }

    @Test fun ownerSampleCountsAsAVoiceOfTheOwnersSide() {
        // Owner mostly quiet in this recording (axis 4), but matches the voice sample.
        val ws = call()
        listOf(60.0, 70.0).forEach { ws += W(0, -33.0, 4, it) }
        assertEquals(2, run(ws).count)
        assertEquals(0, run(ws, ownerSide = 0, ownerVoice = voice(4, 0)).count)
    }

    @Test fun shortWindowFollowsItsNeighbour() {
        val ws = call()
        ws += W(0, -34.0, 2, 60.0)
        ws += W(0, -35.0, -1, 62.3, 0.5) // too short to fingerprint, right after the stranger
        val r = run(ws)
        assertTrue(r.background.last())
    }

    @Test fun aVoiceThatMakesUpMuchOfASideIsAParticipant() {
        // A "quiet" voice that talks more than the owner on that side is someone on the call.
        val ws = call(6)
        (0 until 12).forEach { ws += W(0, -34.0, 2, 40.0 + it * 6) }
        assertEquals(0, run(ws).count)
    }

    @Test fun oneSideOnlyIsNotJudged() {
        val ws = call().map { it.copy(side = 0) }
        assertNotNull(run(ws).skipped)
    }

    @Test fun alignerPutsBackgroundWordsOnTheirOwnLines() {
        val words = listOf(
            Transcript.Word(0, 400, "שלום"), Transcript.Word(450, 900, "מה"), Transcript.Word(950, 1400, "נשמע"),
            Transcript.Word(1500, 1900, "תביא"), Transcript.Word(1950, 2400, "חלב"),
            Transcript.Word(2600, 3000, "הכל"), Transcript.Word(3050, 3500, "טוב"),
        )
        val t = Transcript(listOf(Transcript.Segment(0, 3500, "x")), words)
        val lines = SpeakerAligner.align(t, listOf(SpeakerAligner.Turn(0, 3500, 0)), listOf(1500L..2400L))
        assertEquals(listOf("שלום מה נשמע", "תביא חלב", "הכל טוב"), lines.map { it.text })
        assertEquals(listOf(false, true, false), lines.map { it.background })
    }
}
