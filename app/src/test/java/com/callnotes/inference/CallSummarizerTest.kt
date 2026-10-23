package com.callnotes.inference

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class CallSummarizerTest {

    private val zone = ZoneId.of("Asia/Jerusalem")
    /** Wednesday 21.10.2026, 14:05. */
    private val call = CallContext(LocalDate.of(2026, 10, 21).atTime(14, 5).atZone(zone).toInstant().toEpochMilli(), false, "דני")

    /** Answers by what is asked; records every prompt and how many conversations were opened. */
    private class FakeAnalyzer(private val answer: (String) -> String) : Analyzer {
        val prompts = mutableListOf<String>()
        var conversations = 0
        override val modelId = "fake"
        override suspend fun <T> conversation(block: suspend (Analyzer.Chat) -> T): T {
            conversations++
            return block(object : Analyzer.Chat {
                override suspend fun ask(prompt: String, onChars: (Int) -> Unit): String { prompts += prompt; return answer(prompt) }
            })
        }
        override fun close() {}
    }

    private val shortCall = listOf(
        AnalysisPrompt.Line("דני", "תוכל לשלוח לי את ההצעה מחר?"),
        AnalysisPrompt.Line("אני", "כן, אשלח לך מחר בבוקר"),
        AnalysisPrompt.Line("דני", "ונדבר ביום שלישי"),
    )

    private fun precise(prompt: String) = when {
        "Question 1" in prompt -> """{"summary": "תיאום הצעה", "summary_certain": true,
            "key_points": [{"lines": [1], "text": "דני מחכה להצעה", "certain": true}], "decisions": [], "follow_ups": [], "title": "הצעה לדני"}"""
        "Question 2" in prompt -> """{"tasks": [
            {"quote": "אשלח לך מחר בבוקר", "lines": [2], "owner": "me", "title": "לשלוח את ההצעה", "due_phrase": "מחר", "due": "2026-10-25", "certain": true},
            {"quote": "ונדבר ביום שלישי", "lines": [3], "owner": "me", "title": "לדבר עם דני", "due_phrase": "ביום שלישי", "due": null, "certain": true}]}"""
        "Question 3" in prompt -> """{"tasks": [
            {"quote": "אשלח לך מחר בבוקר", "lines": [2], "owner": "me", "title": "לשלוח את ההצעה", "due_phrase": "מחר", "due": "2026-10-22", "certain": true}],
            "removed": [{"title": "לדבר עם דני", "reason": "polite closing"}]}"""
        else -> error("unexpected prompt")
    }

    @Test fun preciseAsksThreeQuestionsInOneConversation() = runBlocking {
        val fake = FakeAnalyzer(::precise)
        val a = CallSummarizer(fake, precise = true).summarize(shortCall, call, "אני", "דני", zone)
        assertEquals(1, fake.conversations)
        assertEquals(3, fake.prompts.size)
        assertTrue(fake.prompts[0].contains("[1] דני: תוכל לשלוח לי את ההצעה מחר?"))
        assertTrue(fake.prompts[0].contains("2026-10-22 חמישי (מחר)"))
        assertTrue(fake.prompts[0].contains("Outgoing call"))
        assertFalse(fake.prompts[1].contains("[1] דני")) // the transcript is not sent again
        assertEquals("הצעה לדני", a.title)
        assertEquals(1, a.tasks.size) // the review removed one
        assertEquals("2026-10-22", a.tasks.single().dueDate)
    }

    @Test fun unusableReviewKeepsTheFoundTasks() = runBlocking {
        val fake = FakeAnalyzer { p -> if ("Question 3" in p) "sorry" else precise(p) }
        val a = CallSummarizer(fake, precise = true).summarize(shortCall, call, "אני", "דני", zone)
        assertEquals(2, a.tasks.size)
        // "מחר" fixes the wrong date the model gave; "ביום שלישי" gets the next Tuesday, marked unsure.
        assertEquals("2026-10-22", a.tasks[0].dueDate)
        assertEquals("2026-10-27", a.tasks[1].dueDate)
        assertFalse(a.tasks[1].certain)
    }

    @Test fun longCallIsSplitWithOverlapAndNothingIsCut() {
        val lines = (1..400).map { AnalysisPrompt.Line("אני", "שורה מספר $it עם קצת טקסט כדי למלא את השיחה") }
        val parts = CallSummarizer.split(lines)
        assertTrue(parts.size > 1)
        assertEquals(1, parts.first().firstLine)
        assertEquals(400, parts.last().firstLine + parts.last().lines.size - 1)
        // Every line is in some part, and consecutive parts overlap.
        val covered = parts.flatMap { p -> (p.firstLine until p.firstLine + p.lines.size).toList() }.toSet()
        assertEquals((1..400).toSet(), covered)
        parts.zipWithNext().forEach { (a, b) -> assertTrue(b.firstLine <= a.firstLine + a.lines.size - 1) }
    }

    @Test fun shortCallIsOnePart() = assertEquals(1, CallSummarizer.split(shortCall).size)

    @Test fun partsAreMergedKeepingEveryItem() = runBlocking {
        val lines = (1..400).map { AnalysisPrompt.Line("אני", "שורה מספר $it עם קצת טקסט כדי למלא את השיחה") }
        var part = 0
        val fake = FakeAnalyzer { p ->
            when {
                "These are notes on consecutive parts" in p ->
                    // Merges P1+P2 into one, forgets the rest: the rest must come back by itself.
                    """{"summary": "השיחה כולה", "summary_certain": true, "title": "כותרת", "key_points": [{"ids": ["P1", "P2"], "text": "מאוחד"}],
                        "decisions": [], "follow_ups": []}"""
                else -> {
                    part++
                    val first = Regex("lines (\\d+)–").find(p)?.groupValues?.get(1)?.toInt() ?: 1
                    """{"title": "חלק $part", "summary": "סיכום $part", "summary_certain": true,
                        "key_points": [{"text": "נקודה $part", "lines": [$first], "certain": true}], "decisions": [],
                        "tasks": [{"title": "לשלוח את ההצעה", "owner": "me", "quote": "שורה מספר $first", "lines": [$first], "certain": true}],
                        "follow_ups": []}"""
                }
            }
        }
        val a = CallSummarizer(fake, precise = false).summarize(lines, call, "אני", "דני", zone)
        assertTrue(part > 1)
        assertEquals("השיחה כולה", a.summary)
        assertEquals("מאוחד", a.keyPoints.first().text)
        assertEquals(part - 1, a.keyPoints.size) // two merged, none lost
        assertEquals(1, a.tasks.size)            // the same task in every part → one, with all its lines
        assertEquals(part, a.tasks.single().lines.size)
    }

    @Test fun tasksMergedOnlyWhenReallyTheSame() {
        fun t(title: String, lines: List<Int>, owner: String = "me") =
            CallAnalysis.Task(title, owner, null, null, null, lines)
        val out = CallSummarizer.dedupe(listOf(
            t("לשלוח הצעת מחיר לדני", listOf(3)),
            t("לשלוח הצעת מחיר לספק", listOf(9)),   // other person: stays
            t("לשלוח הצעת מחיר", listOf(20)),       // all its words in the first: merged
            t("לשלוח הצעת מחיר לדני", listOf(30), owner = "other"), // same title: merged
        ))
        assertEquals(2, out.size)
        assertEquals(listOf(3, 20, 30), out[0].lines)
    }

    @Test fun mergeBatchesKeepPromptsShort() {
        val sizes = List(10) { 3_000 }
        val batches = CallSummarizer.batches(sizes) { it.sum() }
        assertTrue(batches.size > 1)
        assertTrue(batches.all { it.size >= 2 })
        assertEquals(10, batches.sumOf { it.size })
    }
}
