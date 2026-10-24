package com.callnotes.inference.whisper

/**
 * The words Whisper is told to expect before it listens: the owner's name, the other
 * side's name and the user's own list of names and terms. Spelled the way they should
 * come out, they are recognised far more often (names are Whisper's weakest point).
 * Pure (unit-tested).
 *
 * Kept small and plain on purpose: a comma-separated list, Hebrew-script words only (a
 * Latin contact name could pull the output towards English), at most [MAX_CHARS]
 * characters (the prompt shares Whisper's 224-token text budget with what it hears). A
 * long prompt raises the risk of it being "heard" in silence.
 */
object WhisperPrompt {

    const val MAX_CHARS = 150

    private val hebrew = Regex("[\\u05D0-\\u05EA]")

    fun build(ownerName: String?, contactName: String?, vocabulary: List<String>): String {
        val words = (listOfNotNull(ownerName, contactName) + vocabulary)
            .map { it.trim().replace(Regex("\\s+"), " ").trim(',', '.', ';') }
            .filter { it.isNotEmpty() && hebrew.containsMatchIn(it) && it.none { c -> c.isLetter() && c in 'A'..'z' } }
            .distinct()
        val out = StringBuilder()
        for (w in words) {
            val next = if (out.isEmpty()) w else ", $w"
            if (out.length + next.length + 1 > MAX_CHARS) break
            out.append(next)
        }
        return if (out.isEmpty()) "" else "$out."
    }

    /** The settings field ("ליעד, נתב״ג, הצעת מחיר") as a list. */
    fun parseList(text: String): List<String> =
        text.split(',', '\n', '،').map { it.trim() }.filter { it.isNotEmpty() }
}
