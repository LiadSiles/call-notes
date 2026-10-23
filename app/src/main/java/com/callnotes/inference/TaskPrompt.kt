package com.callnotes.inference

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Turns a voice note ("תזכיר לי מחר בעשר להתקשר לרופא") into tasks: the prompt Gemma gets
 * and the parsing of its JSON answer. Pure (unit-tested); the model call is in
 * [com.callnotes.processing.SummaryWorker]. Dates and times are checked in code the same
 * way as for calls ([AnalysisPrompt.validDate], [AnalysisPrompt.validTime]).
 */
object TaskPrompt {

    /** A voice note is short; anything longer is cut rather than slowing Gemma down. */
    const val MAX_NOTE_CHARS = 3_000

    fun build(text: String, recordedAt: LocalDateTime): String {
        val date = recordedAt.toLocalDate()
        val note = text.trim().take(MAX_NOTE_CHARS)
        return buildString {
            appendLine("The phone's owner recorded a short voice note to add a task to their to-do list. " +
                "Turn it into a single JSON object. Write every text value in Hebrew.")
            appendLine()
            appendLine("Recorded on $date (${date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)}) " +
                "at ${recordedAt.format(DateTimeFormatter.ofPattern("HH:mm"))}.")
            appendLine("What they said (automatic speech recognition — it can mishear words):")
            appendLine(note)
            appendLine()
            appendLine("Return only JSON, no markdown:")
            appendLine("{\"tasks\": [{\"title\": \"what has to be done\", \"due\": \"YYYY-MM-DD or null\", " +
                "\"due_time\": \"HH:MM or null\", \"due_phrase\": \"the words they used for when, or null\", \"certain\": true}]}")
            appendLine("Rules:")
            appendLine("- Usually one task. Several only if they clearly asked for several different things.")
            appendLine("- \"title\": short and clear, starting with the action (\"להתקשר לרופא\"). Drop words like " +
                "\"תזכיר לי\" or \"אני צריך\". Keep names, places and numbers exactly as said.")
            appendLine("- \"due\": compute from the recording date (\"מחר\" is ${date.plusDays(1)}). null if no day was said.")
            appendLine("- \"due_time\": 24-hour time. A clock time as said (\"בחמש\" in the afternoon is 17:00). " +
                "A part of the day without a clock time: morning 09:00, noon 12:00, afternoon 16:00, evening 19:00, night 21:00. " +
                "A time without a day means today if that time is still ahead, else tomorrow. null if no time was said.")
            appendLine("- \"certain\": false if the words look misrecognised or you had to guess.")
            appendLine("- If there is no task in it (silence, noise, unrelated talk), return {\"tasks\": []}.")
            appendLine("- Hebrew acronyms must use the Hebrew gershayim ״, never the ASCII double quote \".")
        }
    }

    /**
     * Parses Gemma's answer into tasks. A time without a valid date is dated today (or
     * tomorrow if it has already passed), so "at five" still reminds.
     */
    fun parse(answer: String, recordedAt: LocalDateTime): List<CallAnalysis.Task> {
        val start = answer.indexOf('{')
        val end = answer.lastIndexOf('}')
        require(start >= 0 && end > start) { "no JSON object in the answer" }
        val raw = answer.substring(start, end + 1)
        val o = try {
            JSONObject(raw)
        } catch (e: org.json.JSONException) {
            JSONObject(AnalysisPrompt.repairQuotes(raw))
        }
        val today = recordedAt.toLocalDate()
        val a = o.optJSONArray("tasks") ?: JSONArray()
        return (0 until a.length()).mapNotNull { i ->
            val t = a.optJSONObject(i) ?: return@mapNotNull null
            val title = t.optString("title").clean() ?: return@mapNotNull null
            val rawDue = t.optString("due").clean()
            var due = rawDue?.let { AnalysisPrompt.validDate(it, today) }
            val time = t.optString("due_time").clean()?.let(AnalysisPrompt::validTime)
            if (due == null && time != null) {
                val at = java.time.LocalTime.parse(time)
                due = (if (at.isAfter(recordedAt.toLocalTime())) today else today.plusDays(1)).toString()
            }
            CallAnalysis.Task(
                title = title,
                owner = "me",
                duePhrase = t.optString("due_phrase").clean(),
                dueDate = due,
                sourceQuote = null,
                certain = t.optBoolean("certain", true) && (rawDue == null || due != null),
                dueTime = time?.takeIf { due != null },
            )
        }
    }

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
}
