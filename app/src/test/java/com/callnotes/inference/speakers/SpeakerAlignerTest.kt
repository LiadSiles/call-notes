package com.callnotes.inference.speakers

import com.callnotes.inference.Transcript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeakerAlignerTest {

    private fun w(start: Long, end: Long, text: String) = Transcript.Word(start, end, text)
    private val turns = listOf(
        SpeakerAligner.Turn(0, 2_000, 0),
        SpeakerAligner.Turn(2_100, 4_000, 1),
        SpeakerAligner.Turn(4_200, 6_000, 0),
    )

    @Test fun wordsBecomeLinesPerSpeaker() {
        val t = Transcript(
            segments = emptyList(),
            words = listOf(
                w(100, 400, "מתי"), w(450, 900, "נפגשים"), w(900, 950, "?"),
                w(2_200, 2_600, "ביום"), w(2_650, 3_100, "שלישי"),
                w(4_300, 4_700, "מעולה"),
            ),
        )
        val lines = SpeakerAligner.align(t, turns)
        assertEquals(listOf("מתי נפגשים?", "ביום שלישי", "מעולה"), lines.map { it.text })
        assertEquals(listOf(0, 1, 0), lines.map { it.speaker })
        assertEquals(2_200L, lines[1].startMs)
        assertEquals(3_100L, lines[1].endMs)
    }

    @Test fun wordBetweenTurnsTakesNearestOrPrevious() {
        // 2 020–2 080 falls in the gap between turn 0 and turn 1 → nearest wins.
        assertEquals(0, SpeakerAligner.speakerFor(1_990, 2_030, turns))
        assertNull(SpeakerAligner.speakerFor(10_000, 10_500, turns))
        val t = Transcript(emptyList(), listOf(w(100, 300, "כן"), w(9_000, 9_200, "טוב")))
        val lines = SpeakerAligner.align(t, turns)
        assertEquals(listOf(0, 0), lines.map { it.speaker }) // far word keeps previous speaker
        assertEquals(2, lines.size)                           // long pause splits the line
    }

    @Test fun withoutWordsSegmentsAreLabelled() {
        val t = Transcript(listOf(Transcript.Segment(2_300, 3_900, "ביום שלישי")))
        assertEquals(1, SpeakerAligner.align(t, turns).single().speaker)
    }

    @Test fun voicePackRoundTripAndMatching() {
        val me = floatArrayOf(1f, 0f, 0f)
        val packed = Voice.pack(mapOf(0 to floatArrayOf(0.1f, 1f, 0f), 1 to floatArrayOf(0.9f, 0.1f, 0f)))
        val speakers = Voice.unpack(packed)
        assertEquals(2, speakers.size)
        assertEquals(1, Voice.whichIsMe(me, speakers))
        assertNull(Voice.whichIsMe(floatArrayOf(0f, 0f, 1f), speakers)) // matches nobody
    }
}
