package com.callnotes.inference

/**
 * Joins per-chunk transcripts into one, removing the text duplicated by the 3 s
 * overlap, and cleans model artefacts. Pure Kotlin — unit-testable without a device.
 */
object TranscriptMerger {

    /** Placeholder for a chunk whose output looked like a hallucination loop. */
    const val UNCLEAR = "[לא ברור]"

    private const val LOOKAROUND = 25 // words at the end of A / start of B compared

    fun merge(segments: List<Transcript.Segment>): List<Transcript.Segment> {
        val cleaned = segments.map { it.copy(text = clean(it.text)) }
        val result = ArrayList<Transcript.Segment>()
        for (seg in cleaned) {
            val prev = result.lastOrNull()
            if (prev == null || seg.text.isBlank() || prev.text.isBlank()) {
                if (seg.text.isNotBlank()) result += seg
                continue
            }
            val trimmed = dropOverlap(prev.text, seg.text)
            if (trimmed.isNotBlank()) result += seg.copy(text = trimmed)
        }
        return result
    }

    /** Removes from [b] the leading words that repeat the end of [a]. */
    fun dropOverlap(a: String, b: String): String {
        val wa = a.split(WS).filter { it.isNotEmpty() }
        val wb = b.split(WS).filter { it.isNotEmpty() }
        val tailA = wa.takeLast(LOOKAROUND).map(::normalize)
        val headB = wb.take(LOOKAROUND).map(::normalize)

        // Longest common run of words between A's tail and B's head.
        var bestLen = 0
        var bestEndInB = -1
        val dp = Array(tailA.size + 1) { IntArray(headB.size + 1) }
        for (i in 1..tailA.size) for (j in 1..headB.size) {
            if (tailA[i - 1].isNotEmpty() && tailA[i - 1] == headB[j - 1]) {
                dp[i][j] = dp[i - 1][j - 1] + 1
                if (dp[i][j] > bestLen) { bestLen = dp[i][j]; bestEndInB = j - 1 }
            }
        }
        // One shared word is likely chance; two or more is the overlap.
        if (bestLen < 2) return b
        return wb.drop(bestEndInB + 1).joinToString(" ")
    }

    /** Trims, collapses whitespace, replaces runaway repetition with [UNCLEAR]. */
    fun clean(text: String): String {
        val t = text.replace(WS, " ").trim()
        if (t.isEmpty()) return t
        return if (looksLikeLoop(t)) UNCLEAR else t
    }

    /** Same word 6+ times in a row, or the same 3-word phrase 4+ times. */
    fun looksLikeLoop(text: String): Boolean {
        val w = text.split(' ').map(::normalize).filter { it.isNotEmpty() }
        var run = 1
        for (i in 1 until w.size) {
            run = if (w[i] == w[i - 1]) run + 1 else 1
            if (run >= 6) return true
        }
        if (w.size >= 12) {
            val counts = HashMap<String, Int>()
            for (i in 0..w.size - 3) {
                val key = "${w[i]} ${w[i + 1]} ${w[i + 2]}"
                val c = (counts[key] ?: 0) + 1
                if (c >= 4) return true
                counts[key] = c
            }
        }
        return false
    }

    /** For comparison only: no niqqud/punctuation, final letters folded, lower case. */
    fun normalize(word: String): String = word
        .replace(NIQQUD, "")
        .replace(PUNCT, "")
        .lowercase()
        .map { FINALS[it] ?: it }
        .joinToString("")

    private val WS = Regex("\\s+")
    private val NIQQUD = Regex("[\\u0591-\\u05C7]")
    private val PUNCT = Regex("[\\p{P}\\p{S}]")
    private val FINALS = mapOf('ך' to 'כ', 'ם' to 'מ', 'ן' to 'נ', 'ף' to 'פ', 'ץ' to 'צ')

    // The Google dialer's spoken notice, if its own recording was on.
    private val ANNOUNCEMENTS = listOf(
        Regex("השיחה (הזאת |הזו )?(מוקלטת|תוקלט|עומדת להיות מוקלטת)[.!]?"),
        Regex("(?i)this call (is|will be) (now )?(being )?recorded[.!]?"),
    )

    /** Removes recording announcements from the first segments. */
    fun stripAnnouncements(segments: List<Transcript.Segment>): List<Transcript.Segment> =
        segments.mapIndexed { i, s ->
            if (i > 1) s else s.copy(text = ANNOUNCEMENTS.fold(s.text) { acc, r -> r.replace(acc, "") }.trim())
        }.filter { it.text.isNotBlank() }
}
