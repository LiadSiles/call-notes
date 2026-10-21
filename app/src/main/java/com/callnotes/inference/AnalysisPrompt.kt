package com.callnotes.inference

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Builds the analysis prompt from a speaker-labelled conversation and parses the model's
 * JSON back. Pure (unit-tested); the model call is in [GemmaAnalyzer].
 */
object AnalysisPrompt {

    /** One line of the conversation as the model sees it. */
    data class Line(val speaker: String, val text: String)

    /** Keep prompts bounded on a phone: longer calls keep their start and end. */
    const val MAX_TRANSCRIPT_CHARS = 12_000

    fun build(lines: List<Line>, call: CallContext, ownerLabel: String?, otherLabel: String?, zone: ZoneId): String {
        val time = Instant.ofEpochMilli(call.startedAt).atZone(zone)
        val date = time.toLocalDate()
        val who = if (ownerLabel != null && otherLabel != null)
            "The phone's owner appears as \"$ownerLabel\". The other side appears as \"$otherLabel\"."
        else
            "Speakers appear as \"דובר 1\" and \"דובר 2\"; it is not known which one is the phone's owner."
        return buildString {
            appendLine("You read the transcript of a phone call and return a single JSON object. Write every text value in Hebrew.")
            appendLine()
            appendLine("Call details:")
            appendLine("- Date: ${date} (${date.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH)}), time ${time.format(DateTimeFormatter.ofPattern("HH:mm"))}")
            call.contactName?.let { appendLine("- Other side's name in the phone's contacts: $it") }
            appendLine("- $who")
            appendLine()
            appendLine("Transcript (automatic speech recognition — may contain mistakes; infer the intended words when obvious):")
            appendLine(transcript(lines))
            appendLine()
            appendLine("Return only JSON, no markdown, with exactly these keys:")
            appendLine("{\"title\": \"up to 8 words\", \"summary\": \"2-4 sentences\", \"key_points\": [\"...\"], " +
                "\"decisions\": [\"...\"], \"tasks\": [{\"title\": \"what has to be done\", " +
                "\"owner\": \"me|other|unknown\", \"due\": \"YYYY-MM-DD or null\", " +
                "\"due_phrase\": \"the words used in the call, or null\", \"quote\": \"short quote from the transcript\"}], " +
                "\"follow_ups\": [\"...\"]}")
            appendLine("Rules:")
            appendLine("- Only tasks that were actually requested, promised or agreed in the call. \"me\" = the phone's owner.")
            appendLine("- \"due\": compute from the call date (for example \"מחר\" is ${date.plusDays(1)}). null if no time was mentioned.")
            appendLine("- Do not invent facts that are not in the transcript. Empty lists are fine.")
            appendLine("- If the transcript has no meaningful content, return empty strings and empty lists.")
        }
    }

    private fun transcript(lines: List<Line>): String {
        val all = lines.joinToString("\n") { if (it.speaker.isEmpty()) it.text else "${it.speaker}: ${it.text}" }
        if (all.length <= MAX_TRANSCRIPT_CHARS) return all
        val head = all.take(MAX_TRANSCRIPT_CHARS * 2 / 3).substringBeforeLast('\n')
        val tail = all.takeLast(MAX_TRANSCRIPT_CHARS / 3).substringAfter('\n')
        return "$head\n[… החלק האמצעי של השיחה הושמט …]\n$tail"
    }

    /**
     * Parses the model's answer. Tolerates code fences and text around the JSON. Due dates
     * are kept only if they parse and fall between the day before the call and two years after.
     */
    fun parse(answer: String, callDate: LocalDate): CallAnalysis {
        val start = answer.indexOf('{')
        val end = answer.lastIndexOf('}')
        require(start >= 0 && end > start) { "no JSON object in the answer" }
        val o = JSONObject(answer.substring(start, end + 1))

        fun strings(key: String): List<String> {
            val a = o.optJSONArray(key) ?: return emptyList()
            return (0 until a.length()).mapNotNull { a.optString(it).clean() }
        }

        val tasks = (o.optJSONArray("tasks") ?: JSONArray()).let { a ->
            (0 until a.length()).mapNotNull { i ->
                val t = a.optJSONObject(i) ?: return@mapNotNull null
                val title = t.optString("title").clean() ?: return@mapNotNull null
                val owner = t.optString("owner").lowercase().takeIf { it in setOf("me", "other") } ?: "unknown"
                CallAnalysis.Task(
                    title = title,
                    owner = owner,
                    duePhrase = t.optString("due_phrase").clean(),
                    dueDate = t.optString("due").clean()?.let { validDate(it, callDate) },
                    confidence = 1f,
                    sourceQuote = t.optString("quote").clean(),
                )
            }
        }
        return CallAnalysis(
            title = o.optString("title").clean() ?: "",
            summary = o.optString("summary").clean() ?: "",
            keyPoints = strings("key_points"),
            decisions = strings("decisions"),
            tasks = tasks,
            followUps = strings("follow_ups"),
            language = "he",
        )
    }

    fun validDate(s: String, callDate: LocalDate): String? = runCatching {
        val d = LocalDate.parse(s.take(10))
        if (d.isBefore(callDate.minusDays(1)) || d.isAfter(callDate.plusYears(2))) null else d.toString()
    }.getOrNull()

    /** Summary, points, decisions and follow-ups as stored JSON (tasks live in their own table). */
    fun toJson(a: CallAnalysis): String = JSONObject()
        .put("title", a.title)
        .put("summary", a.summary)
        .put("key_points", JSONArray(a.keyPoints))
        .put("decisions", JSONArray(a.decisions))
        .put("follow_ups", JSONArray(a.followUps))
        .toString()

    fun fromJson(json: String?): CallAnalysis? = runCatching {
        val o = JSONObject(json ?: return null)
        fun list(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
        CallAnalysis(o.optString("title"), o.optString("summary"), list("key_points"), list("decisions"),
            emptyList(), list("follow_ups"), "he")
    }.getOrNull()

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
}
