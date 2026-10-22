package com.callnotes.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptTextTest {

    @Test fun detectsLoops() {
        assertTrue(TranscriptText.looksLikeLoop("כן כן כן כן כן כן"))
        assertTrue(TranscriptText.looksLikeLoop("אני לא יודע אני לא יודע אני לא יודע אני לא יודע"))
        assertFalse(TranscriptText.looksLikeLoop("כן כן, בסדר, נדבר מחר"))
    }

    @Test fun normalizesPunctuationNiqqudAndFinals() {
        assertEquals("שלומ עולמ", TranscriptText.normalizeText("  שָׁלוֹם,   עולם! "))
    }

    @Test fun stripsRecordingAnnouncement() {
        val segs = listOf(Transcript.Segment(0, 28_000, "השיחה מוקלטת. הלו, מדבר דני"))
        assertEquals("הלו, מדבר דני", TranscriptText.stripAnnouncements(segs)[0].text)
    }
}
