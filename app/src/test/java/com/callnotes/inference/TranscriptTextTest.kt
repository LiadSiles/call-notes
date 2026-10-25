package com.callnotes.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptTextTest {

    @Test fun dropsOnlyTheRepeatsOfALoop() {
        assertEquals("כן כן", TranscriptText.withoutRepeats("כן כן כן כן כן כן"))
        assertEquals("שלום, הגעת למרפאה אני לא יודע אני לא יודע",
            TranscriptText.withoutRepeats("שלום, הגעת למרפאה אני לא יודע אני לא יודע אני לא יודע אני לא יודע"))
        assertEquals("כן כן, בסדר, נדבר מחר", TranscriptText.withoutRepeats("כן כן, בסדר, נדבר מחר"))
        assertEquals("מה שלומך? טוב, טוב, תודה", TranscriptText.withoutRepeats("מה שלומך? טוב, טוב, תודה"))
    }

    @Test fun fillers() {
        assertTrue(TranscriptText.isFiller("תודה רבה."))
        assertFalse(TranscriptText.isFiller("תודה רבה, אשלח לך מחר"))
    }

    @Test fun normalizesPunctuationNiqqudAndFinals() {
        assertEquals("שלומ עולמ", TranscriptText.normalizeText("  שָׁלוֹם,   עולם! "))
    }

    @Test fun stripsRecordingAnnouncement() {
        val segs = listOf(Transcript.Segment(0, 28_000, "השיחה מוקלטת. הלו, מדבר דני"))
        assertEquals("הלו, מדבר דני", TranscriptText.stripAnnouncements(segs)[0].text)
    }
}
