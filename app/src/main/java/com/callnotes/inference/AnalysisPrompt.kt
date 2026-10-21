package com.callnotes.inference

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Builds the analysis prompt from a speaker-labelled conversation and parses the model's
 * JSON back. Pure (unit-tested); the model call is in [GemmaAnalyzer].
 *
 * Accuracy rules (0.6): transcript lines are numbered; every point, decision, task and
 * follow-up must cite the lines it comes from and say whether it is certain. The app then
 * checks what it can — cited lines exist, a task's quote really appears in them, the due
 * date is plausible — and marks anything that fails as not certain.
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
            appendLine("Accuracy matters more than completeness: a short correct answer is better than a long one with guesses.")
            appendLine()
            appendLine("Call details:")
            appendLine("- Date: $date (${date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)}), time ${time.format(DateTimeFormatter.ofPattern("HH:mm"))}")
            call.contactName?.let { appendLine("- Other side's name in the phone's contacts: $it") }
            appendLine("- $who")
            appendLine()
            appendLine("Transcript, one numbered line per utterance (automatic speech recognition — it can mishear words):")
            appendLine(transcript(lines))
            appendLine()
            appendLine("Return only JSON, no markdown, with exactly these keys:")
            appendLine("{\"title\": \"up to 8 words\", \"summary\": \"2-4 sentences\", \"summary_certain\": true, " +
                "\"key_points\": [{\"text\": \"...\", \"lines\": [3, 4], \"certain\": true}], " +
                "\"decisions\": [{\"text\": \"...\", \"lines\": [7], \"certain\": true}], " +
                "\"tasks\": [{\"title\": \"what has to be done\", \"owner\": \"me|other|unknown\", " +
                "\"due\": \"YYYY-MM-DD or null\", \"due_phrase\": \"the words used in the call, or null\", " +
                "\"quote\": \"the exact words from the transcript\", \"lines\": [12], \"certain\": true}], " +
                "\"follow_ups\": [{\"text\": \"...\", \"lines\": [15], \"certain\": true}]}")
            appendLine("Rules:")
            appendLine("- Every item must cite in \"lines\" the line numbers above that support it. Only cite lines that really say it.")
            appendLine("- \"certain\": false when the supporting lines are unclear, look misrecognised, contradict each other, " +
                "or you are inferring rather than reading it. Never guess names, numbers, places or dates — if unclear, mark false.")
            appendLine("- \"summary_certain\": false if large parts of the transcript are unintelligible.")
            appendLine("- Only tasks that were actually requested, promised or agreed in the call. \"me\" = the phone's owner. " +
                "\"quote\" must be copied word for word from the cited line.")
            appendLine("- \"due\": compute from the call date (for example \"מחר\" is ${date.plusDays(1)}). null if no time was mentioned.")
            appendLine("- Do not invent facts that are not in the transcript. Empty lists are fine.")
            appendLine("- If the transcript has no meaningful content, return empty strings and empty lists.")
            appendLine("- Hebrew acronyms and abbreviations (נתב״ג, צה״ל, ת״א) must use the Hebrew gershayim ״ or geresh ׳, " +
                "never the ASCII double quote \", which would break the JSON.")
        }
    }

    private fun transcript(lines: List<Line>): String {
        val numbered = lines.mapIndexed { i, l -> "[${i + 1}] " + if (l.speaker.isEmpty()) l.text else "${l.speaker}: ${l.text}" }
        val all = numbered.joinToString("\n")
        if (all.length <= MAX_TRANSCRIPT_CHARS) return all
        val head = all.take(MAX_TRANSCRIPT_CHARS * 2 / 3).substringBeforeLast('\n')
        val tail = all.takeLast(MAX_TRANSCRIPT_CHARS / 3).substringAfter('\n')
        return "$head\n[… החלק האמצעי של השיחה הושמט …]\n$tail"
    }

    /**
     * Parses and checks the model's answer. [lineTexts] are the transcript lines (same
     * order as in the prompt) used to verify citations and quotes. Tolerates code fences and
     * text around the JSON; repairs Hebrew acronyms written with ASCII quotes.
     */
    fun parse(answer: String, callDate: LocalDate, lineTexts: List<String> = emptyList()): CallAnalysis {
        val start = answer.indexOf('{')
        val end = answer.lastIndexOf('}')
        require(start >= 0 && end > start) { "no JSON object in the answer" }
        val raw = answer.substring(start, end + 1)
        val o = try {
            JSONObject(raw)
        } catch (e: org.json.JSONException) {
            JSONObject(repairQuotes(raw)) // Hebrew acronyms written with ASCII quotes (נתב"ג)
        }
        val n = lineTexts.size

        fun lines(a: JSONArray?): List<Int> =
            if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optInt(it, -1).takeIf { v -> v >= 1 } }.distinct()

        /** Certain only if the model says so AND every cited line exists (when we know the lines). */
        fun checked(certain: Boolean, cited: List<Int>): Pair<List<Int>, Boolean> {
            if (n == 0) return cited to certain
            val valid = cited.filter { it <= n }
            return valid to (certain && valid.isNotEmpty() && valid.size == cited.size)
        }

        fun points(key: String): List<CallAnalysis.Point> {
            val a = o.optJSONArray(key) ?: return emptyList()
            return (0 until a.length()).mapNotNull { i ->
                val item = a.opt(i)
                if (item is JSONObject) {
                    val text = item.optString("text").clean() ?: return@mapNotNull null
                    val (ls, ok) = checked(item.optBoolean("certain", false), lines(item.optJSONArray("lines")))
                    CallAnalysis.Point(text, ls, ok)
                } else {
                    // Older/looser answers: a plain string, no citation → not verified.
                    a.optString(i).clean()?.let { CallAnalysis.Point(it, emptyList(), n == 0) }
                }
            }
        }

        val tasks = (o.optJSONArray("tasks") ?: JSONArray()).let { a ->
            (0 until a.length()).mapNotNull { i ->
                val t = a.optJSONObject(i) ?: return@mapNotNull null
                val title = t.optString("title").clean() ?: return@mapNotNull null
                val owner = t.optString("owner").lowercase().takeIf { it in setOf("me", "other") } ?: "unknown"
                val quote = t.optString("quote").clean()
                val (ls, citedOk) = checked(t.optBoolean("certain", false), lines(t.optJSONArray("lines")))
                val rawDue = t.optString("due").clean()
                val due = rawDue?.let { validDate(it, callDate) }
                // A quote that isn't in the cited lines, or a due date we had to drop, isn't verified.
                val quoteOk = quote == null || n == 0 || ls.any { contains(lineTexts[it - 1], quote) }
                CallAnalysis.Task(
                    title = title,
                    owner = owner,
                    duePhrase = t.optString("due_phrase").clean(),
                    dueDate = due,
                    confidence = 1f,
                    sourceQuote = quote,
                    lines = ls,
                    certain = citedOk && quoteOk && (rawDue == null || due != null),
                )
            }
        }
        return CallAnalysis(
            title = o.optString("title").clean() ?: "",
            summary = o.optString("summary").clean() ?: "",
            keyPoints = points("key_points"),
            decisions = points("decisions"),
            tasks = tasks,
            followUps = points("follow_ups"),
            language = "he",
            summaryCertain = o.optBoolean("summary_certain", true),
        )
    }

    /** Loose containment for Hebrew ASR text: punctuation, niqqud and final letters ignored. */
    fun contains(line: String, quote: String): Boolean {
        val l = TranscriptMerger.normalizeText(line)
        val q = TranscriptMerger.normalizeText(quote)
        if (q.isEmpty()) return true
        if (l.contains(q)) return true
        // Allow small differences: most of the quote's words in the line.
        val words = q.split(' ').filter { it.length > 1 }
        if (words.isEmpty()) return false
        return words.count { it in l } >= (words.size * 0.75)
    }

    /**
     * Fixes the most common way the model breaks its JSON in Hebrew: an ASCII `"` used as
     * gershayim inside a string (`"נתב"ג"`). First letter-quote-letter becomes ״; then any
     * remaining `"` inside a string that isn't followed by `,` `:` `}` `]` (or the end) is
     * escaped, since it can't be the closing quote.
     */
    fun repairQuotes(json: String): String {
        val hebrew = Regex("([\\u05D0-\\u05EA])\"([\\u05D0-\\u05EA])")
        val s = hebrew.replace(json, "$1״$2")
        val out = StringBuilder(s.length + 16)
        var inString = false
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            when {
                inString && ch == '\\' && i + 1 < s.length -> { out.append(ch).append(s[i + 1]); i += 2; continue }
                ch == '"' && !inString -> inString = true
                ch == '"' && inString -> {
                    var j = i + 1
                    while (j < s.length && s[j].isWhitespace()) j++
                    if (j >= s.length || s[j] in ",:}]") inString = false
                    else { out.append("\\\""); i++; continue }
                }
            }
            out.append(ch)
            i++
        }
        return out.toString()
    }

    fun validDate(s: String, callDate: LocalDate): String? = runCatching {
        val d = LocalDate.parse(s.take(10))
        if (d.isBefore(callDate.minusDays(1)) || d.isAfter(callDate.plusYears(2))) null else d.toString()
    }.getOrNull()

    /** Summary, points, decisions and follow-ups as stored JSON (tasks live in their own table). */
    fun toJson(a: CallAnalysis): String = JSONObject()
        .put("title", a.title)
        .put("summary", a.summary)
        .put("summary_certain", a.summaryCertain)
        .put("key_points", pointsJson(a.keyPoints))
        .put("decisions", pointsJson(a.decisions))
        .put("follow_ups", pointsJson(a.followUps))
        .toString()

    private fun pointsJson(ps: List<CallAnalysis.Point>) = JSONArray(ps.map {
        JSONObject().put("text", it.text).put("lines", JSONArray(it.lines)).put("certain", it.certain)
    })

    /** Reads stored analysis; summaries stored before 0.6 (plain strings) load as uncited points. */
    fun fromJson(json: String?): CallAnalysis? = runCatching {
        val o = JSONObject(json ?: return null)
        fun list(k: String): List<CallAnalysis.Point> {
            val a = o.optJSONArray(k) ?: return emptyList()
            return (0 until a.length()).mapNotNull { i ->
                when (val item = a.opt(i)) {
                    is JSONObject -> CallAnalysis.Point(
                        item.optString("text"),
                        item.optJSONArray("lines")?.let { ls -> (0 until ls.length()).map { ls.optInt(it) } } ?: emptyList(),
                        item.optBoolean("certain", true),
                    )
                    is String -> CallAnalysis.Point(item)
                    else -> null
                }
            }
        }
        CallAnalysis(o.optString("title"), o.optString("summary"), list("key_points"), list("decisions"),
            emptyList(), list("follow_ups"), "he", o.optBoolean("summary_certain", true))
    }.getOrNull()

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
}
