package com.callnotes.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceFinderTest {
    private val lines = listOf(
        "היי, מה שלומך?",
        "תקשיב, אני אשלח לך מחר בבוקר את הצעת המחיר",
        "מעולה, ונקבע פגישה ביום שלישי בעשר במשרד",
        "סגור, תודה רבה",
    )

    @Test fun findsTaskByItsWords() {
        assertEquals(1, SourceFinder.bestLine("לשלוח את הצעת המחיר", lines))
        assertEquals(2, SourceFinder.bestLine("פגישה ביום שלישי", lines))
    }

    @Test fun hebrewPrefixesDoNotStopAMatch() {
        // "במשרד" in the line, "משרד" in the point; "והצעת" vs "הצעת".
        assertEquals(2, SourceFinder.bestLine("הפגישה תהיה במשרד", lines))
        assertEquals(1, SourceFinder.bestLine("והצעת מחיר", lines))
    }

    @Test fun noMatchGivesNull() {
        assertNull(SourceFinder.bestLine("טיסה לאילת", lines))
        assertNull(SourceFinder.bestLine("את של על", lines))
    }
}
