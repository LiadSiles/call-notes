package com.callnotes.inference

/**
 * Finds the transcript line an extracted item (key point, task, quote) most likely came
 * from, by shared words. Used when the summary didn't cite line numbers (summaries before
 * 0.6, or the model left them out), so every item can still open its place in the call.
 * Hebrew-aware: niqqud, punctuation and final letters are ignored, and a one-letter prefix
 * (ו ה ב ל מ ש כ) is stripped from longer words. Pure (unit-tested).
 */
object SourceFinder {

    /** Share of the query's content words a line must contain to count as the source. */
    private const val MIN_SCORE = 0.34

    /** 0-based index of the best matching line, or null if nothing matches well enough. */
    fun bestLine(query: String, lines: List<String>): Int? {
        val q = contentWords(query)
        if (q.isEmpty() || lines.isEmpty()) return null
        var best = -1
        var bestScore = 0.0
        lines.forEachIndexed { i, line ->
            val forms = contentWords(line).flatMap(::forms).toSet()
            if (forms.isEmpty()) return@forEachIndexed
            val hit = q.filter { w -> forms(w).any { it in forms } }
            // Long words weigh a little more; ties go to the earlier line.
            val score = hit.size.toDouble() / q.size + 0.001 * hit.sumOf { it.length }
            if (score > bestScore) { bestScore = score; best = i }
        }
        return best.takeIf { it >= 0 && bestScore >= MIN_SCORE }
    }

    internal fun contentWords(text: String): List<String> =
        TranscriptMerger.normalizeText(text).split(' ').filter { it.length >= 2 && it !in STOP }.distinct()

    /** The word as written and without a one-letter prefix (ו ה ב ל מ ש כ), for longer words. */
    internal fun forms(w: String): List<String> =
        if (w.length >= 4 && w[0] in PREFIXES) listOf(w, w.drop(1)) else listOf(w)

    private const val PREFIXES = "והבלמשכ"

    /** Common Hebrew function words (normalised: final letters folded). */
    private val STOP = setOf(
        "את", "של", "על", "עמ", "זה", "זו", "זאת", "אני", "אתה", "את", "הוא", "היא", "אנחנו", "המ", "הנ",
        "לא", "כנ", "מה", "גמ", "יש", "אינ", "כל", "או", "אמ", "כי", "רק", "עוד", "אז", "לו", "לי", "לכ",
        "שלי", "שלכ", "שלו", "שלה", "הזה", "הזאת", "אבל", "מאוד", "פה", "שמ", "איכ", "למה", "מתי", "צריכ",
        "יהיה", "היה", "הייתי", "אוקיי", "טוב", "בסדר", "נו", "אה", "אממ", "כאילו",
    )
}
