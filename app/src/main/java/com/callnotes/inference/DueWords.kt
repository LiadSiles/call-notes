package com.callnotes.inference

import java.time.LocalDate

/**
 * Checks a task's date against the words said for it ("מחר", "ביום שלישי", "עד היום"),
 * because small models slip on date arithmetic. Pure (unit-tested).
 *
 * - Words that fix one day (היום / הערב / מחר / מחרתיים / בעוד שבוע) set it, whatever the
 *   model wrote.
 * - A day of the week allows its next occurrence after the call or the one a week later
 *   ("שלישי הבא" is said both ways); any other date is replaced by the next occurrence and
 *   the task marked not certain, so the user checks it.
 * - Anything else (explicit dates, "בסוף החודש") is left to the model and the range check.
 */
object DueWords {

    private const val NOT_LETTER = "(?<![\\u05D0-\\u05EA])"
    private const val END = "(?![\\u05D0-\\u05EA])"
    /** Optional attached prefix (ב/ל/ו) and "יום". */
    private const val PREFIX = "$NOT_LETTER(?:[בלו]|עד\\s+)?(?:יום\\s+)?"

    private val today = Regex("$NOT_LETTER(?:[בלו]|עד\\s+)?(?:היום|הערב|הלילה)$END")
    private val tomorrow = Regex("$NOT_LETTER[בלו]?מחר$END")
    private val dayAfter = Regex("$NOT_LETTER[בלו]?מחרתיים$END")
    private val inAWeek = Regex("בעוד\\s+שבוע$END")
    /** Not "שני ימים" (two days) and the like. */
    private val weekday = Regex("$PREFIX(ראשון|שני|שלישי|רביעי|חמישי|שישי|שבת)$END(?!\\s*(?:ימים|שבועות|חודשים))")

    /** [task] with its date checked against its words ([CallAnalysis.Task.duePhrase]). */
    fun check(task: CallAnalysis.Task, callDate: LocalDate): CallAnalysis.Task {
        val words = task.duePhrase ?: return task
        val fixed = when {
            dayAfter.containsMatchIn(words) -> callDate.plusDays(2)
            tomorrow.containsMatchIn(words) -> callDate.plusDays(1)
            inAWeek.containsMatchIn(words) -> callDate.plusDays(7)
            today.containsMatchIn(words) -> callDate
            else -> null
        }
        if (fixed != null) return task.copy(dueDate = fixed.toString())
        val day = weekday.find(words)?.groupValues?.get(1) ?: return task
        val next = nextWeekday(callDate, AnalysisPrompt.HEBREW_DAYS.indexOf(day))
        val given = task.dueDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        return if (given == next || given == next.plusDays(7)) task
        else task.copy(dueDate = next.toString(), certain = false)
    }

    /** The first day after [from] that is [sundayFirst] (0 = Sunday … 6 = Saturday). */
    fun nextWeekday(from: LocalDate, sundayFirst: Int): LocalDate {
        var d = from.plusDays(1)
        while (d.dayOfWeek.value % 7 != sundayFirst) d = d.plusDays(1)
        return d
    }
}
