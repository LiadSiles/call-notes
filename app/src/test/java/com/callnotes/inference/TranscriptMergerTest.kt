package com.callnotes.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptMergerTest {

    @Test fun dropsOverlappingWords() {
        val a = "אז נפגשים ביום שלישי בשעה שמונה בבוקר"
        val b = "בשעה שמונה בבוקר ליד המשרד שלך"
        assertEquals("ליד המשרד שלך", TranscriptMerger.dropOverlap(a, b))
    }

    @Test fun overlapIgnoresPunctuationAndFinalLetters() {
        val a = "אני אשלח לך את ההצעה עד מחר."
        val b = "עד מחר, ואז נדבר שוב"
        assertEquals("ואז נדבר שוב", TranscriptMerger.dropOverlap(a, b))
    }

    @Test fun keepsTextWhenOnlyOneWordMatches() {
        val a = "תתקשר אליי מחר"
        val b = "מחר יש לי פגישה"
        assertEquals(b, TranscriptMerger.dropOverlap(a, b))
    }

    @Test fun detectsLoops() {
        assertTrue(TranscriptMerger.looksLikeLoop("כן כן כן כן כן כן"))
        assertTrue(TranscriptMerger.looksLikeLoop("אני לא יודע אני לא יודע אני לא יודע אני לא יודע"))
        assertFalse(TranscriptMerger.looksLikeLoop("כן כן, בסדר, נדבר מחר"))
    }

    @Test fun mergeSkipsBlankChunksAndTrims() {
        val segs = listOf(
            Transcript.Segment(0, 28_000, "שלום מה שלומך היום"),
            Transcript.Segment(25_000, 53_000, "   "),
            Transcript.Segment(50_000, 78_000, "שלומך היום? טוב תודה"),
        )
        val merged = TranscriptMerger.merge(segs)
        assertEquals(2, merged.size)
        assertEquals("טוב תודה", merged[1].text)
    }

    @Test fun stripsRecordingAnnouncement() {
        val segs = listOf(Transcript.Segment(0, 28_000, "השיחה מוקלטת. הלו, מדבר דני"))
        assertEquals("הלו, מדבר דני", TranscriptMerger.stripAnnouncements(segs)[0].text)
    }
}
