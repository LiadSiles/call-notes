package com.callnotes.inference

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/**
 * Prompts of the "precise" summary (three questions in one conversation with the model)
 * and of the merge step that joins the parts of a long call. Pure (unit-tested); the order
 * of the steps is in [CallSummarizer]. Instructions are in English (small models follow
 * them better); every answer is written in Hebrew.
 */
object SummaryPrompts {

    /** First message: the call (or one part of it) and question 1 — what it was about. */
    fun opening(
        lines: List<AnalysisPrompt.Line>,
        call: CallContext,
        ownerLabel: String?,
        otherLabel: String?,
        zone: ZoneId,
        firstLine: Int,
        part: Int,
        parts: Int,
    ): String = buildString {
        appendLine("You will answer several questions about one phone call, one at a time. Write every text value in Hebrew.")
        appendLine("Return only JSON, no markdown. Accuracy matters more than completeness. " +
            "Never guess names, numbers, places or dates.")
        appendLine()
        append(AnalysisPrompt.context(lines, call, ownerLabel, otherLabel, zone, firstLine, part, parts))
        appendLine()
        appendLine("Question 1 — what " + (if (parts > 1) "this part of the call" else "the call") + " was about:")
        appendLine("{\"summary\": \"2-4 sentences\", \"summary_certain\": true, " +
            "\"key_points\": [{\"lines\": [3], \"text\": \"...\", \"certain\": true}], " +
            "\"decisions\": [{\"lines\": [7], \"text\": \"...\", \"certain\": true}], " +
            "\"follow_ups\": [{\"lines\": [9], \"text\": \"...\", \"certain\": true}], " +
            "\"title\": \"up to 8 words\"}")
        appendLine("Rules:")
        appendLine("- key_points: information that was exchanged. decisions: only what both sides agreed on. " +
            "follow_ups: open questions or things left to check, that nobody took on yet.")
        appendLine("- Cite in \"lines\" only lines that really say it. \"certain\": false if those lines are unclear " +
            "or you are inferring rather than reading.")
        appendLine("- \"summary_certain\": false if large parts of the transcript are unintelligible.")
        appendLine("- Write \"title\" last, as a short name for the summary.")
        appendLine("- Do not list tasks here; they are the next question.")
        appendLine(AnalysisPrompt.ACRONYMS)
    }

    /** Question 2 — every commitment, quote first. [callDate] dates the worked example. */
    fun tasks(callDate: LocalDate): String = buildString {
        appendLine("Question 2 — commitments. Go through the transcript line by line and find every place where:")
        appendLine("- someone asked the other to do something and it was accepted (\"request\"),")
        appendLine("- someone promised to do something (\"promise\"),")
        appendLine("- both agreed that something will be done, or set a call or meeting for a time (\"agreement\").")
        appendLine(AnalysisPrompt.ARE_TASKS)
        appendLine(AnalysisPrompt.NOT_TASKS)
        appendLine()
        appendLine("{\"tasks\": [{\"quote\": \"...\", \"lines\": [2], \"kind\": \"promise|request|agreement\", " +
            "\"owner\": \"me|other|unknown\", \"title\": \"...\", \"due_phrase\": \"... or null\", " +
            "\"due\": \"YYYY-MM-DD or null\", \"due_time\": \"HH:MM or null\", \"certain\": true}]}")
        appendLine("Rules:")
        appendLine("- Fill the fields in this order: first copy \"quote\" word for word from the line, then decide everything else from it.")
        appendLine("- \"owner\" is who has to do it. \"me\" = the phone's owner. A task for someone who is not in the call " +
            "counts only if one of the speakers took it on (then it is theirs).")
        appendLine("- \"title\": the action, starting with a verb, up to 10 words (\"לשלוח את הצעת המחיר\").")
        appendLine("- \"due_phrase\": the words about when, copied as said, or null.")
        appendLine("- \"due\": only if a day was said; take it from the calendar. \"עד יום שלישי\" → that Tuesday.")
        appendLine("- \"due_time\": only a clock time that was said, 24-hour (\"בחמש\" in the afternoon → 17:00).")
        appendLine("- An empty list is a fine answer.")
        appendLine()
        appendLine("Example from another call:")
        appendLine("[1] אני: תוכל לשלוח לי את החוזה עד מחר?")
        appendLine("[2] יוסי: אין בעיה, אשלח בבוקר")
        appendLine("[3] יוסי: אולי נקפוץ פעם לבקר")
        appendLine("Answer:")
        appendLine("{\"tasks\": [{\"quote\": \"אין בעיה, אשלח בבוקר\", \"lines\": [1, 2], \"kind\": \"request\", " +
            "\"owner\": \"other\", \"title\": \"לשלוח את החוזה\", \"due_phrase\": \"עד מחר\", " +
            "\"due\": \"${callDate.plusDays(1)}\", \"due_time\": null, \"certain\": true}]}")
        appendLine("Line 3 is not a task: it is hypothetical.")
    }

    /** Question 3 — the model checks its own list against the transcript. */
    fun review(): String = buildString {
        appendLine("Question 3 — check your answer to question 2. For each task, reread its lines and the lines around them, then:")
        appendLine("- remove it if it is not really a commitment (only mentioned, hypothetical, already done, refused, " +
            "or changed later in the call);")
        appendLine("- merge duplicates: the same action said more than once → one task citing all its lines;")
        appendLine("- fix \"owner\" if the lines show the other person has to do it;")
        appendLine("- fix \"due\" / \"due_time\" if they don't match \"due_phrase\" and the calendar;")
        appendLine("- set \"certain\": false where the words look misrecognised.")
        appendLine("Return the final list in the same format, plus what you removed:")
        appendLine("{\"tasks\": [...], \"removed\": [{\"title\": \"...\", \"reason\": \"...\"}]}")
    }

    /**
     * Joins the notes of consecutive parts of a long call into notes for the whole call.
     * Items are listed with ids (P1, D1, F1); the answer merges repeats by id, so every
     * item keeps its source lines and nothing can be invented.
     */
    fun merge(notes: List<CallAnalysis>, call: CallContext, zone: ZoneId): String = buildString {
        val date = java.time.Instant.ofEpochMilli(call.startedAt).atZone(zone).toLocalDate()
        appendLine("These are notes on consecutive parts of one phone call, in order; together they cover the whole call. " +
            "Write the notes for the whole call. Write every text value in Hebrew. Return only JSON, no markdown.")
        appendLine("Call date: $date" + (call.contactName?.let { ", with $it" } ?: "") + ".")
        appendLine()
        notes.forEachIndexed { i, n ->
            appendLine("Part ${i + 1} of ${notes.size}" + (if (!n.summaryCertain) " (transcript partly unclear)" else "") +
                ": ${n.summary.ifBlank { "(nothing meaningful)" }}")
        }
        appendLine()
        listItems("Key points", "P", notes.flatMap { it.keyPoints })
        listItems("Decisions", "D", notes.flatMap { it.decisions })
        listItems("Follow-ups", "F", notes.flatMap { it.followUps })
        appendLine("Return:")
        appendLine("{\"summary\": \"3-6 sentences about the whole call\", \"summary_certain\": true, " +
            "\"key_points\": [{\"ids\": [\"P1\", \"P4\"], \"text\": \"...\"}], " +
            "\"decisions\": [{\"ids\": [\"D1\"], \"text\": \"...\"}], " +
            "\"follow_ups\": [{\"ids\": [\"F1\"], \"text\": \"...\"}], \"title\": \"up to 8 words\"}")
        appendLine("Rules:")
        appendLine("- Keep every item. Only items that say the same thing are merged into one, listing all their ids.")
        appendLine("- Never add an item without ids. \"text\" keeps the meaning; you may shorten it. Do not add facts.")
        appendLine("- The summary covers the whole call, the most important first — not only the first part.")
        appendLine("- \"summary_certain\": false if parts of the call were unclear.")
        appendLine(AnalysisPrompt.ACRONYMS)
    }

    private fun StringBuilder.listItems(heading: String, prefix: String, items: List<CallAnalysis.Point>) {
        if (items.isEmpty()) return
        appendLine("$heading:")
        items.forEachIndexed { i, p -> appendLine("$prefix${i + 1}${if (p.certain) "" else " (unsure)"}: ${p.text}") }
        appendLine()
    }

    /**
     * Reads the merge answer. Each merged item gets the lines of its ids and is certain only
     * if all of them were; items the answer left out are added back as they were, so the
     * merge can shorten the notes but never lose one.
     */
    fun parseMerge(answer: String, notes: List<CallAnalysis>): CallAnalysis {
        val start = answer.indexOf('{')
        val end = answer.lastIndexOf('}')
        require(start >= 0 && end > start) { "no JSON object in the answer" }
        val raw = answer.substring(start, end + 1)
        val o = try {
            JSONObject(raw)
        } catch (e: org.json.JSONException) {
            JSONObject(AnalysisPrompt.repairQuotes(raw))
        }
        fun merged(key: String, prefix: String, items: List<CallAnalysis.Point>): List<CallAnalysis.Point> {
            val used = mutableSetOf<Int>()
            val out = mutableListOf<CallAnalysis.Point>()
            val a = o.optJSONArray(key) ?: JSONArray()
            for (i in 0 until a.length()) {
                val item = a.optJSONObject(i) ?: continue
                val ids = item.optJSONArray("ids") ?: continue
                val picked = (0 until ids.length()).mapNotNull { j ->
                    ids.optString(j).trim().removePrefix(prefix).toIntOrNull()?.minus(1)?.takeIf { it in items.indices }
                }.distinct().filter { it !in used }
                if (picked.isEmpty()) continue
                used += picked
                val text = item.optString("text").trim().takeIf { it.isNotEmpty() && it != "null" } ?: items[picked.first()].text
                out += CallAnalysis.Point(
                    text,
                    picked.flatMap { items[it].lines }.distinct().sorted(),
                    picked.all { items[it].certain },
                )
            }
            items.indices.filter { it !in used }.forEach { out += items[it] }
            return out
        }
        val summary = o.optString("summary").trim().takeIf { it.isNotEmpty() && it != "null" }
            ?: notes.joinToString(" ") { it.summary }.trim()
        return CallAnalysis(
            title = o.optString("title").trim().takeIf { it.isNotEmpty() && it != "null" } ?: notes.first().title,
            summary = summary,
            keyPoints = merged("key_points", "P", notes.flatMap { it.keyPoints }),
            decisions = merged("decisions", "D", notes.flatMap { it.decisions }),
            tasks = emptyList(),
            followUps = merged("follow_ups", "F", notes.flatMap { it.followUps }),
            summaryCertain = o.optBoolean("summary_certain", true) && notes.all { it.summaryCertain },
        )
    }

    /** The parts' notes put together without the model (when a merge answer is unusable). */
    fun joinWithoutModel(notes: List<CallAnalysis>): CallAnalysis = CallAnalysis(
        title = notes.first().title,
        summary = notes.joinToString(" ") { it.summary }.trim(),
        keyPoints = notes.flatMap { it.keyPoints },
        decisions = notes.flatMap { it.decisions },
        tasks = emptyList(),
        followUps = notes.flatMap { it.followUps },
        summaryCertain = notes.all { it.summaryCertain },
    )
}
