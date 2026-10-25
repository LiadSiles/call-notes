package com.callnotes.inference

/**
 * Text helpers for Hebrew ASR output: comparison-friendly normalisation (used to match
 * quotes, task titles and search-like lookups), Whisper's repetition loops, and the
 * dialer's spoken "this call is recorded" notice. Pure Kotlin, unit-tested.
 */
object TranscriptText {

    /**
     * Whisper's loops ("אני לא יודע אני לא יודע אני לא יודע…", one word over and over):
     * marks every block of 1–4 tokens that repeats the two blocks before it. A phrase said
     * twice stays; from the third time on it goes. Only the repeats are dropped, never
     * the real words around them (until 0.17 a whole line with a loop was dropped).
     */
    fun repeats(tokens: List<String>): BooleanArray {
        val norm = tokens.map(::normalize)
        val drop = BooleanArray(tokens.size)
        var i = 0
        while (i < tokens.size) {
            var n = 4
            var found = 0
            while (n >= 1) {
                if (i - 2 * n >= 0 && i + n <= tokens.size) {
                    val cur = norm.subList(i, i + n)
                    if (cur.any { it.isNotEmpty() } && cur == norm.subList(i - n, i) && cur == norm.subList(i - 2 * n, i - n)) {
                        found = n; break
                    }
                }
                n--
            }
            if (found > 0) { for (k in i until i + found) drop[k] = true; i += found } else i++
        }
        return drop
    }

    /** [text] without its looping repeats. */
    fun withoutRepeats(text: String): String {
        val tokens = text.split(WS).filter { it.isNotEmpty() }
        val drop = repeats(tokens)
        return tokens.filterIndexed { i, _ -> !drop[i] }.joinToString(" ")
    }

    /** Words without looping repeats (same rule as [withoutRepeats]). */
    fun withoutRepeatedWords(words: List<Transcript.Word>): List<Transcript.Word> {
        val drop = repeats(words.map { it.text })
        return words.filterIndexed { i, _ -> !drop[i] }
    }

    /**
     * Whisper's stock phrases for audio without speech (music, noise): a line that is only
     * one of these, in a stretch read a second time, is not kept.
     */
    fun isFiller(text: String): Boolean = normalizeText(text) in FILLERS

    private val FILLERS = setOf(
        "תודה", "תודה רבה", "תודה שצפיתמ", "תודה על הצפייה", "תודה רבה על הצפייה", "כתוביות", "תרגומ", "מוזיקה",
        "thank you", "thanks for watching", "music",
    )

    /** [normalize] applied word by word, joined with single spaces. */
    fun normalizeText(text: String): String =
        text.split(WS).map(::normalize).filter { it.isNotEmpty() }.joinToString(" ")

    /** For comparison only: no niqqud or punctuation, final letters folded, lower case. */
    fun normalize(word: String): String {
        val out = StringBuilder(word.length)
        for (ch in word.replace(NIQQUD, "").replace(PUNCT, "").lowercase()) out.append(FINALS[ch] ?: ch)
        return out.toString()
    }

    /** Removes the dialer's recording notice from the first two segments. */
    fun stripAnnouncements(segments: List<Transcript.Segment>): List<Transcript.Segment> =
        segments.mapIndexed { i, s ->
            if (i > 1) s else s.copy(text = ANNOUNCEMENTS.fold(s.text) { acc, r -> r.replace(acc, "") }.trim())
        }.filter { it.text.isNotBlank() }

    private val WS = Regex("\\s+")
    private val NIQQUD = Regex("[\\u0591-\\u05C7]")
    private val PUNCT = Regex("[\\p{P}\\p{S}]")
    private val FINALS = mapOf('ך' to 'כ', 'ם' to 'מ', 'ן' to 'נ', 'ף' to 'פ', 'ץ' to 'צ')

    // The Google dialer's spoken notice, if its own recording was on.
    private val ANNOUNCEMENTS = listOf(
        Regex("השיחה (הזאת |הזו )?(מוקלטת|תוקלט|עומדת להיות מוקלטת)[.!]?"),
        Regex("(?i)this call (is|will be) (now )?(being )?recorded[.!]?"),
    )
}
