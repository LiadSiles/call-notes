package com.callnotes.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DueWordsTest {

    /** Wednesday. */
    private val call = LocalDate.of(2026, 10, 21)
    private fun task(phrase: String?, due: String?) = CallAnalysis.Task("t", "me", phrase, due, null)

    @Test fun fixedWordsSetTheDay() {
        assertEquals("2026-10-22", DueWords.check(task("עד מחר", "2026-10-25"), call).dueDate)
        assertEquals("2026-10-23", DueWords.check(task("מחרתיים", null), call).dueDate)
        assertEquals("2026-10-21", DueWords.check(task("עד סוף היום", null), call).dueDate)
        assertEquals("2026-10-28", DueWords.check(task("בעוד שבוע", null), call).dueDate)
    }

    @Test fun weekdayAcceptsThisOrNextWeek() {
        assertTrue(DueWords.check(task("ביום שלישי", "2026-10-27"), call).certain)
        assertTrue(DueWords.check(task("שלישי הבא", "2026-11-03"), call).certain)
        val wrong = DueWords.check(task("בשלישי", "2026-10-29"), call)
        assertEquals("2026-10-27", wrong.dueDate)
        assertFalse(wrong.certain)
    }

    @Test fun notADay() {
        assertEquals("2026-11-05", DueWords.check(task("תוך שני ימים", "2026-11-05"), call).dueDate) // "two days", not Monday
        assertEquals(null, DueWords.check(task("כשיהיה לך זמן", null), call).dueDate)
        assertEquals("2026-10-30", DueWords.check(task(null, "2026-10-30"), call).dueDate)
    }

    @Test fun nextWeekdayIsAfterTheCall() {
        assertEquals(LocalDate.of(2026, 10, 28), DueWords.nextWeekday(call, 3)) // Wednesday → next Wednesday
    }
}
