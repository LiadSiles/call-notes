package com.callnotes.inference.speakers

import com.callnotes.inference.Transcript

/**
 * Whisper's words grouped into short windows (split at pauses, ≤ 3 s): the units on which
 * background speech is judged ([BackgroundSpeech]). Until 0.18 these windows were also
 * clustered into the two speakers; that is now [SpeakerDecoder]'s job, per word. Pure
 * (unit-tested).
 */
object VoiceClusters {

    data class Window(val firstWord: Int, val lastWord: Int, val startMs: Long, val endMs: Long) {
        val durationMs get() = endMs - startMs
    }

    private const val PAUSE_MS = 400L
    private const val MAX_WINDOW_MS = 3_000L
    /** Shorter windows aren't fingerprinted (too little audio to trust). */
    const val MIN_EMBED_MS = 1_000L

    fun windows(words: List<Transcript.Word>): List<Window> {
        val out = ArrayList<Window>()
        var first = 0
        for (i in 1..words.size) {
            val end = i == words.size ||
                words[i].startMs - words[i - 1].endMs > PAUSE_MS ||
                words[i].endMs - words[first].startMs > MAX_WINDOW_MS
            if (end) {
                out += Window(first, i - 1, words[first].startMs, maxOf(words[i - 1].endMs, words[first].startMs + 1))
                first = i
            }
        }
        return if (words.isEmpty()) emptyList() else out
    }
}
