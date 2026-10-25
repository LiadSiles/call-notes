package com.callnotes.inference

import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Summary and tasks of one call with the on-device model — the whole call, never cut.
 *
 * - A call that fits one prompt is read in one piece; a longer one is split into parts of
 *   about [PART_CHARS] characters (a couple of lines overlapping, so nothing said across a
 *   boundary is lost). Lines keep their numbers in the whole call.
 * - Each part, "precise" mode: one conversation of three questions — what it was about,
 *   every commitment (quote first), then a review of that list. The model reads the part
 *   once; the follow-up questions reuse it. "Quick" mode: one question for everything.
 * - The parts' notes are merged by the model ([SummaryPrompts.merge]) — in rounds if there
 *   are many — keeping every item and its source lines. Tasks are merged in code: the same
 *   task said in two parts becomes one.
 * - Finally every task's date is checked against the words said for it ([DueWords]).
 *
 * Pure apart from the [Analyzer] (unit-tested with a fake one).
 */
class CallSummarizer(
    private val analyzer: Analyzer,
    private val precise: Boolean,
    private val listener: Listener = object : Listener {},
    /** The model reasons before answering: smaller parts, so the reasoning fits in its context. */
    private val thinking: Boolean = false,
) {

    /** Progress, for the notification and the log (never the call's words). */
    interface Listener {
        fun step(step: Step, part: Int, parts: Int) {}
        fun chars(written: Int) {}
        fun log(text: String) {}
    }

    enum class Step { NOTES, TASKS, REVIEW, MERGE }

    /** One stretch of the call; [firstLine] is the number of its first line in the whole call. */
    data class Part(val lines: List<AnalysisPrompt.Line>, val firstLine: Int)

    /** Characters written by the model, all steps together (for the log). */
    var charsOut = 0
        private set

    suspend fun summarize(
        lines: List<AnalysisPrompt.Line>,
        call: CallContext,
        ownerLabel: String?,
        otherLabel: String?,
        zone: ZoneId,
    ): CallAnalysis {
        val callDate = Instant.ofEpochMilli(call.startedAt).atZone(zone).toLocalDate()
        // Nothing to read (every line was background speech): no model call.
        if (lines.isEmpty()) return CallAnalysis("", "", emptyList(), emptyList(), emptyList(), emptyList(), summaryCertain = false)
        // Texts by line number, to check what the model cites (left-out lines stay empty).
        val numbers = lines.mapIndexed { i, l -> AnalysisPrompt.numberOf(l, i, 1) }
        val texts = MutableList(numbers.maxOrNull() ?: 0) { "" }
        lines.forEachIndexed { i, l -> texts[numbers[i] - 1] = l.text }
        val parts = if (thinking) split(lines, THINKING_SINGLE_MAX_CHARS, THINKING_PART_CHARS) else split(lines)
        if (parts.size > 1) listener.log("long call: ${lines.size} lines in ${parts.size} parts")
        val notes = parts.mapIndexed { i, p ->
            if (precise) precisePart(p, i + 1, parts.size, call, ownerLabel, otherLabel, zone, callDate, texts)
            else quickPart(p, i + 1, parts.size, call, ownerLabel, otherLabel, zone, callDate, texts)
        }
        val whole = if (notes.size == 1) notes.single() else merge(notes, call, zone)
        val tasks = dedupe(notes.flatMap { it.tasks }).map { DueWords.check(it, callDate) }
        return whole.copy(tasks = tasks)
    }

    private suspend fun quickPart(
        p: Part, part: Int, parts: Int, call: CallContext, owner: String?, other: String?,
        zone: ZoneId, callDate: LocalDate, texts: List<String>,
    ): CallAnalysis {
        listener.step(Step.NOTES, part, parts)
        val started = System.currentTimeMillis()
        val answer = analyzer.ask(AnalysisPrompt.build(p.lines, call, owner, other, zone, p.firstLine, part, parts), ::written)
        charsOut += answer.length
        val result = parseLogged(answer, "part $part/$parts") { AnalysisPrompt.parse(it, callDate, texts) }
        listener.log("part $part/$parts: ${secs(started)} · ${result.keyPoints.size} points, ${result.tasks.size} tasks")
        return result
    }

    private suspend fun precisePart(
        p: Part, part: Int, parts: Int, call: CallContext, owner: String?, other: String?,
        zone: ZoneId, callDate: LocalDate, texts: List<String>,
    ): CallAnalysis = analyzer.conversation { chat ->
        var t = System.currentTimeMillis()
        listener.step(Step.NOTES, part, parts)
        val a1 = chat.ask(SummaryPrompts.opening(p.lines, call, owner, other, zone, p.firstLine, part, parts), ::written)
        charsOut += a1.length
        // Question 1 asks for no tasks; any that come anyway are ignored (question 2 is for them).
        val notes = parseLogged(a1, "part $part/$parts notes") { AnalysisPrompt.parse(it, callDate, texts) }.copy(tasks = emptyList())
        val notesTime = secs(t)

        t = System.currentTimeMillis()
        listener.step(Step.TASKS, part, parts)
        val a2 = chat.ask(SummaryPrompts.tasks(callDate), ::written)
        charsOut += a2.length
        val found = parseLogged(a2, "part $part/$parts tasks") { AnalysisPrompt.parse(it, callDate, texts) }.tasks
        val tasksTime = secs(t)

        // The review may only remove or correct; if its answer is unusable, the list stands.
        var reviewed = found
        var reviewNote = "no tasks, no review"
        if (found.isNotEmpty()) {
            t = System.currentTimeMillis()
            listener.step(Step.REVIEW, part, parts)
            reviewed = try {
                val a3 = chat.ask(SummaryPrompts.review(), ::written)
                charsOut += a3.length
                AnalysisPrompt.parse(a3, callDate, texts).tasks
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                listener.log("part $part/$parts: review answer unusable (${e.javaClass.simpleName}); keeping the list")
                found
            }
            reviewNote = "review ${secs(t)} → ${reviewed.size} kept"
        }
        listener.log("part $part/$parts: notes $notesTime (${notes.keyPoints.size} points, ${notes.decisions.size} decisions) · " +
            "tasks $tasksTime (${found.size}) · $reviewNote")
        notes.copy(tasks = reviewed)
    }

    /**
     * Notes of many parts → notes of the whole call. Merges in rounds when one prompt would
     * be too long (very long calls); a merge answer that can't be read joins the notes as
     * they are, so nothing is lost either way.
     */
    private suspend fun merge(notes: List<CallAnalysis>, call: CallContext, zone: ZoneId): CallAnalysis {
        var level = notes
        var round = 1
        while (level.size > 1) {
            val batches = batches(level) { SummaryPrompts.merge(it, call, zone).length }
            level = batches.mapIndexed { i, batch ->
                if (batch.size == 1) return@mapIndexed batch.single()
                listener.step(Step.MERGE, i + 1, batches.size)
                val started = System.currentTimeMillis()
                try {
                    val answer = analyzer.ask(SummaryPrompts.merge(batch, call, zone), ::written)
                    charsOut += answer.length
                    SummaryPrompts.parseMerge(answer, batch).also {
                        listener.log("merge round $round, ${batch.size} parts: ${secs(started)}")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    listener.log("merge round $round unusable (${e.javaClass.simpleName}); notes joined as they are")
                    SummaryPrompts.joinWithoutModel(batch)
                }
            }
            round++
        }
        return level.single()
    }

    private fun written(chars: Int) = listener.chars(chars)

    private inline fun <T> parseLogged(answer: String, what: String, parse: (String) -> T): T = try {
        parse(answer)
    } catch (e: Exception) {
        // The answer is call content: log only its size.
        listener.log("$what: answer not valid JSON (${answer.length} chars): ${e.javaClass.simpleName}")
        throw e
    }

    private fun secs(since: Long) = "%.1f s".format((System.currentTimeMillis() - since) / 1000.0)

    companion object {
        /** Up to this many characters of transcript are read in one piece. */
        const val SINGLE_MAX_CHARS = 9_000
        /** Size of each part of a longer call. */
        const val PART_CHARS = 6_000
        /** The same with thinking on (the reasoning takes room in the context too). */
        const val THINKING_SINGLE_MAX_CHARS = 6_000
        const val THINKING_PART_CHARS = 4_500
        /** Lines repeated at the start of the next part, so a sentence across the cut is seen whole. */
        const val OVERLAP_LINES = 2
        /** Longest merge prompt; more parts are merged in rounds. */
        const val MERGE_MAX_CHARS = 10_000

        private fun size(l: AnalysisPrompt.Line) = l.speaker.length + l.text.length + 8

        /** The call in one part if it fits, else in parts of about [partChars] with a small overlap. */
        fun split(
            lines: List<AnalysisPrompt.Line>,
            singleMax: Int = SINGLE_MAX_CHARS,
            partChars: Int = PART_CHARS,
        ): List<Part> {
            if (lines.sumOf(::size) <= singleMax) return listOf(Part(lines, 1))
            val parts = mutableListOf<Part>()
            var start = 0
            while (start < lines.size) {
                var end = start
                var chars = 0
                while (end < lines.size && (end == start || chars + size(lines[end]) <= partChars)) {
                    chars += size(lines[end]); end++
                }
                parts += Part(lines.subList(start, end), start + 1)
                if (end >= lines.size) break
                // Next part starts a little earlier, but always moves forward.
                start = maxOf(end - OVERLAP_LINES, start + 1)
            }
            return parts
        }

        /** Consecutive groups whose merge prompt stays under [MERGE_MAX_CHARS] (at least two per group). */
        fun <T> batches(items: List<T>, promptChars: (List<T>) -> Int): List<List<T>> {
            if (promptChars(items) <= MERGE_MAX_CHARS) return listOf(items)
            val out = mutableListOf<List<T>>()
            var current = mutableListOf<T>()
            for (item in items) {
                if (current.size >= 2 && promptChars(current + item) > MERGE_MAX_CHARS) {
                    out += current
                    current = mutableListOf()
                }
                current += item
            }
            if (current.isNotEmpty()) out += current
            // A lone last group would never shrink: fold it into the previous one.
            if (out.size > 1 && out.last().size == 1) {
                val last = out.removeAt(out.lastIndex)
                out[out.lastIndex] = out.last() + last
            }
            return out
        }

        /**
         * The same task found twice (in two parts, or said twice) becomes one (see [same]).
         * The first keeps its place; the
         * later mention adds its lines and, if it has one, its date (later in the call
         * usually corrects earlier).
         */
        fun dedupe(tasks: List<CallAnalysis.Task>): List<CallAnalysis.Task> {
            val out = mutableListOf<CallAnalysis.Task>()
            for (t in tasks) {
                val i = out.indexOfFirst { same(it, t) }
                if (i < 0) { out += t; continue }
                val a = out[i]
                val laterDate = t.dueDate != null
                out[i] = a.copy(
                    lines = (a.lines + t.lines).distinct().sorted(),
                    dueDate = if (laterDate) t.dueDate else a.dueDate,
                    dueTime = if (laterDate) t.dueTime else a.dueTime,
                    duePhrase = if (laterDate) t.duePhrase else a.duePhrase,
                    sourceQuote = a.sourceQuote ?: t.sourceQuote,
                    owner = if (a.owner == "unknown") t.owner else a.owner,
                    certain = a.certain || t.certain,
                )
            }
            return out
        }

        /**
         * Strict on purpose — a wrong merge hides a real task: the same title; or, for the
         * same person, all the words of one title in the other ("לשלוח הצעת מחיר" /
         * "לשלוח הצעת מחיר לדני"); or mostly the same words and a shared source line (the
         * overlap between parts). "…לדני" and "…לספק" stay two tasks.
         */
        private fun same(a: CallAnalysis.Task, b: CallAnalysis.Task): Boolean {
            if (TranscriptText.normalizeText(a.title) == TranscriptText.normalizeText(b.title)) return true
            val sameOwner = a.owner == b.owner || a.owner == "unknown" || b.owner == "unknown"
            if (!sameOwner) return false
            val overlap = TaskMatcher.overlap(a.title, b.title)
            return overlap >= 1.0 || overlap >= TaskMatcher.MIN_OVERLAP && a.lines.any { it in b.lines }
        }
    }
}
