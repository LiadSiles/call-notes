package com.callnotes.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class TaskPromptTest {

    /** Friday 23.10.2026, 15:45. */
    private val now = LocalDateTime.of(2026, 10, 23, 15, 45)

    @Test fun dateAndTimeAreKept() {
        val t = TaskPrompt.parse(
            """{"tasks": [{"title": "להתקשר לרופא", "due": "2026-10-24", "due_time": "10:00", "due_phrase": "מחר בעשר", "certain": true}]}""",
            now,
        ).single()
        assertEquals("להתקשר לרופא", t.title)
        assertEquals("2026-10-24", t.dueDate)
        assertEquals("10:00", t.dueTime)
        assertEquals("me", t.owner)
        assertTrue(t.certain)
    }

    @Test fun timeWithoutDayIsTodayOrTomorrow() {
        val later = TaskPrompt.parse("""{"tasks": [{"title": "א", "due": null, "due_time": "17:00"}]}""", now).single()
        assertEquals("2026-10-23", later.dueDate)
        val earlier = TaskPrompt.parse("""{"tasks": [{"title": "ב", "due_time": "9:30"}]}""", now).single()
        assertEquals("2026-10-24", earlier.dueDate)
        assertEquals("09:30", earlier.dueTime)
    }

    @Test fun badDateOrTimeIsDroppedAndMarkedUnsure() {
        val t = TaskPrompt.parse("""{"tasks": [{"title": "א", "due": "2020-01-01", "due_time": "25:00", "certain": true}]}""", now).single()
        assertNull(t.dueDate)
        assertNull(t.dueTime)
        assertFalse(t.certain)
    }

    @Test fun noTaskAndCodeFences() {
        assertTrue(TaskPrompt.parse("```json\n{\"tasks\": []}\n```", now).isEmpty())
        assertTrue(TaskPrompt.parse("""{"tasks": [{"title": ""}]}""", now).isEmpty())
    }

    @Test fun hebrewAcronymWithAsciiQuoteIsRepaired() {
        val t = TaskPrompt.parse("""{"tasks": [{"title": "לנסוע לנתב"ג", "due": null}]}""", now).single()
        assertEquals("לנסוע לנתב״ג", t.title)
    }

    @Test fun promptCarriesDateTimeAndNote() {
        val p = TaskPrompt.build("תזכיר לי מחר להתקשר לדני", now)
        assertTrue(p.contains("2026-10-23 (Friday)"))
        assertTrue(p.contains("15:45"))
        assertTrue(p.contains("תזכיר לי מחר להתקשר לדני"))
        assertTrue(p.contains("2026-10-24"))
    }

    @Test fun validTime() {
        assertEquals("17:00", AnalysisPrompt.validTime("17:00"))
        assertEquals("09:05", AnalysisPrompt.validTime("9:05"))
        assertEquals("23:59", AnalysisPrompt.validTime("23:59:00"))
        assertNull(AnalysisPrompt.validTime("24:00"))
        assertNull(AnalysisPrompt.validTime("בחמש"))
    }
}
