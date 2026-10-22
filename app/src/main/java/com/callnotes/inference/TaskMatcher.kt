package com.callnotes.inference

/**
 * Decides whether a task from a new call repeats an open task from an earlier call
 * ("לשלוח את ההצעה" said on Monday and again on Wednesday). Deliberately strict: a wrong
 * merge hides a real task, a missed one only shows it twice. Pure (unit-tested).
 */
object TaskMatcher {

    /** Share of words both titles must have in common (of the shorter one). */
    const val MIN_OVERLAP = 0.75

    data class Candidate(val title: String, val owner: String?, val dueDate: String?)

    fun same(a: Candidate, b: Candidate): Boolean {
        if (a.owner != null && b.owner != null && a.owner != b.owner) return false
        if (a.dueDate != null && b.dueDate != null && a.dueDate != b.dueDate) return false
        return overlap(a.title, b.title) >= MIN_OVERLAP
    }

    /** Shared content words / content words of the shorter title (Hebrew prefixes folded). */
    fun overlap(a: String, b: String): Double {
        val wa = SourceFinder.contentWords(a)
        val wb = SourceFinder.contentWords(b)
        if (wa.size < 2 || wb.size < 2) return if (wa.isNotEmpty() && wa == wb) 1.0 else 0.0
        val (short, long) = if (wa.size <= wb.size) wa to wb else wb to wa
        val longForms = long.flatMap(SourceFinder::forms).toSet()
        val hit = short.count { w -> SourceFinder.forms(w).any { it in longForms } }
        return hit.toDouble() / short.size
    }

    /** Index of the first [open] task that [task] repeats, or null. */
    fun findOriginal(task: Candidate, open: List<Candidate>): Int? =
        open.indexOfFirst { same(task, it) }.takeIf { it >= 0 }
}
