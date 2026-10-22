package com.callnotes.inference

/**
 * Text helpers for Hebrew ASR output: comparison-friendly normalisation (used to match
 * quotes, task titles and search-like lookups), Whisper's repetition loops, and the
 * dialer's spoken "this call is recorded" notice. Pure Kotlin, unit-tested.
 */
object TranscriptText {

    /** Same word 6+ times in a row, or the same 3-word phrase 4+ times: a decoding loop. */
    fun looksLikeLoop(text: String): Boolean {
        val w = text.split(WS).map(::normalize).filter { it.isNotEmpty() }
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
