package com.callnotes.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class AnalysisPromptTest {

    private val callDate = LocalDate.of(2026, 10, 21)
    private val lines = listOf("מתי נפגשים?", "אשלח לך מחר את הצעת המחיר", "ביום שלישי בעשר", "סגור")

    @Test fun citedAndQuotedItemsStayCertain() {
        val answer = """
            ```json
            {"title": "תיאום פגישה", "summary": "קבעו להיפגש.", "summary_certain": true,
             "key_points": [{"text": "פגישה ביום שלישי", "lines": [3], "certain": true}],
             "decisions": [], "follow_ups": [],
             "tasks": [{"title": "לשלוח הצעת מחיר", "owner": "me", "due": "2026-10-22", "due_phrase": "מחר",
                        "quote": "אשלח לך מחר", "lines": [2], "certain": true}]}
            ```
        """.trimIndent()
        val a = AnalysisPrompt.parse(answer, callDate, lines)
        assertEquals(listOf(3), a.keyPoints.single().lines)
        assertTrue(a.keyPoints.single().certain)
        val t = a.tasks.single()
        assertEquals(listOf(2), t.lines)
        assertTrue(t.certain)
        assertEquals("2026-10-22", t.dueDate)
    }

    @Test fun unverifiableItemsBecomeUncertain() {
        val answer = """{"title": "x", "summary": "y",
             "key_points": [{"text": "שורה שלא קיימת", "lines": [9], "certain": true},
                            {"text": "המודל לא בטוח", "lines": [1], "certain": false},
                            {"text": "בלי ציטוט שורות", "certain": true}],
             "decisions": [], "follow_ups": [],
             "tasks": [{"title": "ציטוט לא נמצא", "owner": "other", "quote": "נדבר בחמישי", "lines": [4], "certain": true},
                       {"title": "תאריך לא סביר", "owner": "Other", "due": "2019-01-01", "lines": [3], "certain": true}]}"""
        val a = AnalysisPrompt.parse(answer, callDate, lines)
        assertEquals(listOf(false, false, false), a.keyPoints.map { it.certain })
        assertEquals(emptyList<Int>(), a.keyPoints[0].lines) // out-of-range line dropped
        assertFalse(a.tasks[0].certain)                       // quote isn't in line 4
        assertFalse(a.tasks[1].certain)                       // due date had to be dropped
        assertNull(a.tasks[1].dueDate)
        assertEquals("other", a.tasks[1].owner)
    }

    @Test fun promptNumbersLinesAndNamesSpeakers() {
        val p = AnalysisPrompt.build(
            listOf(AnalysisPrompt.Line("אני", "מתי נפגשים?"), AnalysisPrompt.Line("דני", "ביום שלישי")),
            CallContext(startedAt = 1_792_580_000_000, incoming = true, contactName = "דני"),
            ownerLabel = "אני", otherLabel = "דני", zone = ZoneId.of("Asia/Jerusalem"),
        )
        assertTrue(p.contains("[1] אני: מתי נפגשים?\n[2] דני: ביום שלישי"))
        assertTrue(p.contains("\"certain\""))
    }

    @Test fun promptKnowsOwnerNameWordsAndWhoSpeaksFirst() {
        val lines = listOf(AnalysisPrompt.Line("דובר 1", "הלו"), AnalysisPrompt.Line("דובר 2", "ליעד, תשלח לי את זה"))
        val incoming = AnalysisPrompt.build(
            lines,
            CallContext(1_792_580_000_000, incoming = true, contactName = "דני", ownerName = "ליעד", vocabulary = listOf("נתב״ג")),
            ownerLabel = null, otherLabel = null, zone = ZoneId.of("Asia/Jerusalem"),
        )
        assertTrue(incoming.contains("The phone's owner is called \"ליעד\""))
        assertTrue(incoming.contains("first speaker is usually the owner"))
        assertTrue(incoming.contains("נתב״ג"))
        assertTrue(incoming.contains("NOT tasks"))
        val voip = AnalysisPrompt.build(
            lines, CallContext(1_792_580_000_000, incoming = false, contactName = null, directionKnown = false),
            ownerLabel = null, otherLabel = null, zone = ZoneId.of("Asia/Jerusalem"),
        )
        assertFalse(voip.contains("first speaker"))
        assertFalse(voip.contains("is called"))
    }

    @Test fun storedJsonRoundTripsAndOldFormatLoads() {
        val a = CallAnalysis("כותרת", "סיכום", listOf(CallAnalysis.Point("א", listOf(1, 2), false)),
            listOf(CallAnalysis.Point("ב", listOf(3))), emptyList(), listOf(CallAnalysis.Point("ג")), false)
        assertEquals(a, AnalysisPrompt.fromJson(AnalysisPrompt.toJson(a)))
        val old = AnalysisPrompt.fromJson("""{"title":"t","summary":"s","key_points":["x"],"decisions":[],"follow_ups":[]}""")!!
        assertEquals(listOf(CallAnalysis.Point("x")), old.keyPoints)
    }

    @Test fun quoteMatchingIgnoresPunctuationAndSmallDifferences() {
        assertTrue(AnalysisPrompt.contains("אשלח לך, מחר את הצעת המחיר!", "אשלח לך מחר"))
        assertTrue(AnalysisPrompt.contains("אני אשלח לך מחר בבוקר את ההצעה", "אשלח לך מחר את ההצעה"))
        assertFalse(AnalysisPrompt.contains("סגור", "נדבר בחמישי"))
    }
}

class AnalysisPromptRepairTest {
    private val callDate = LocalDate.of(2026, 10, 21)

    @Test fun hebrewAcronymWithAsciiQuoteIsRepaired() {
        val answer = "{\"title\": \"תכנון נסיעה לנתב\"ג והארגונים\", \"summary\": \"הדוברים דנים בתחבורה לנתב\"ג.\", " +
            "\"key_points\": [{\"text\": \"צה\"ל\", \"lines\": [], \"certain\": true}], \"decisions\": [], \"tasks\": [], \"follow_ups\": []}"
        val a = AnalysisPrompt.parse(answer, callDate)
        assertEquals("תכנון נסיעה לנתב״ג והארגונים", a.title)
        assertEquals("הדוברים דנים בתחבורה לנתב״ג.", a.summary)
        assertEquals("צה״ל", a.keyPoints.single().text)
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

    @Test fun longerCallsGetLongerSummaries() {
        fun of(chars: Int) = AnalysisPrompt.summaryLength(listOf(AnalysisPrompt.Line("", "א".repeat(chars))))
        assertEquals("1-2 sentences", of(300))
        assertEquals("2-4 sentences", of(2_000))
        assertEquals("4-6 sentences", of(4_000))
        assertEquals("6-8 sentences", of(7_000))
    }

    @Test fun taskQuestionAsksForTitlesThatStandAlone() {
        val q = SummaryPrompts.tasks(callDate)
        assertTrue(q.contains("a week later without the call"))
        assertTrue(q.contains("\"other\" if the other side has to do it"))
        // The worked example shows a promise titled with what it is about.
        assertTrue(q.contains("לבדוק את מחיר ההובלה ולחזור ליוסי"))
        assertTrue(SummaryPrompts.review().contains("would not understand without the call"))
    }
}
