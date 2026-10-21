package com.callnotes.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class AnalysisPromptTest {

    private val callDate = LocalDate.of(2026, 10, 21)

    @Test fun parsesFencedJsonAndValidatesDates() {
        val answer = """
            ```json
            {"title": "תיאום פגישה", "summary": "קבעו להיפגש.", "key_points": ["פגישה ביום שלישי"],
             "decisions": [], "follow_ups": [null, ""],
             "tasks": [
               {"title": "לשלוח הצעת מחיר", "owner": "me", "due": "2026-10-22", "due_phrase": "מחר", "quote": "אשלח לך מחר"},
               {"title": "לאשר תאריך", "owner": "Other", "due": "2019-01-01", "due_phrase": null, "quote": null},
               {"title": "", "owner": "me"}
             ]}
            ```
        """.trimIndent()
        val a = AnalysisPrompt.parse(answer, callDate)
        assertEquals("תיאום פגישה", a.title)
        assertEquals(listOf("פגישה ביום שלישי"), a.keyPoints)
        assertEquals(emptyList<String>(), a.followUps)
        assertEquals(2, a.tasks.size)                       // empty title dropped
        assertEquals("me", a.tasks[0].owner)
        assertEquals("2026-10-22", a.tasks[0].dueDate)
        assertEquals("other", a.tasks[1].owner)              // case-insensitive
        assertNull(a.tasks[1].dueDate)                       // far in the past → dropped
        assertNull(a.tasks[1].duePhrase)
    }

    @Test fun promptNamesSpeakersAndDate() {
        val p = AnalysisPrompt.build(
            listOf(AnalysisPrompt.Line("אני", "מתי נפגשים?"), AnalysisPrompt.Line("דני", "ביום שלישי")),
            CallContext(startedAt = 1_792_580_000_000, incoming = true, contactName = "דני"),
            ownerLabel = "אני", otherLabel = "דני", zone = ZoneId.of("Asia/Jerusalem"),
        )
        assertTrue(p.contains("אני: מתי נפגשים?\nדני: ביום שלישי"))
        assertTrue(p.contains("\"אני\""))
        assertTrue(p.contains("Date: "))
    }

    @Test fun storedJsonRoundTrips() {
        val a = CallAnalysis("כותרת", "סיכום", listOf("א"), listOf("ב"), emptyList(), listOf("ג"), "he")
        val back = AnalysisPrompt.fromJson(AnalysisPrompt.toJson(a))!!
        assertEquals(a.copy(tasks = emptyList()), back)
    }
}

class AnalysisPromptRepairTest {
    private val callDate = java.time.LocalDate.of(2026, 10, 21)

    @Test fun hebrewAcronymWithAsciiQuoteIsRepaired() {
        // The answer that failed on the POCO (shortened).
        val answer = "{\"title\": \"תכנון נסיעה לנתב\"ג והארגונים\", \"summary\": \"הדוברים דנים בתחבורה לנתב\"ג.\", " +
            "\"key_points\": [\"צה\"ל\"], \"decisions\": [], \"tasks\": [], \"follow_ups\": []}"
        val a = AnalysisPrompt.parse(answer, callDate)
        assertEquals("תכנון נסיעה לנתב״ג והארגונים", a.title)
        assertEquals("הדוברים דנים בתחבורה לנתב״ג.", a.summary)
        assertEquals(listOf("צה״ל"), a.keyPoints)
    }

    @Test fun strayQuoteBeforeNonHebrewIsEscaped() {
        val answer = "{\"title\": \"שיחה על \"Zoom\" מחר\", \"summary\": \"\", \"key_points\": [], " +
            "\"decisions\": [], \"tasks\": [], \"follow_ups\": []}"
        assertEquals("שיחה על \"Zoom\" מחר", AnalysisPrompt.parse(answer, callDate).title)
    }

    @Test fun validJsonIsUntouched() {
        val json = "{\"title\": \"a \\\"b\\\" c\"}"
        assertEquals(json, AnalysisPrompt.repairQuotes(json))
    }
}
