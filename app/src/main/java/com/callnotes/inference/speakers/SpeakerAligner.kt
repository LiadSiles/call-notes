package com.callnotes.inference.speakers

import com.callnotes.inference.Transcript

/**
 * Joins Whisper's words with speaker turns (from [LevelSpeakers] or [VoiceClusters]) into
 * a conversation: each word gets the speaker whose turn overlaps it most, and consecutive
 * words of one speaker become one line. Pure logic (unit-tested).
 */
object SpeakerAligner {

    data class Turn(val startMs: Long, val endMs: Long, val speaker: Int)

    /** A pause this long starts a new line even for the same speaker. */
    private const val PAUSE_MS = 2_500L
    /** A word with no overlapping turn takes the nearest turn within this distance. */
    private const val NEAREST_MS = 1_000L

    fun align(transcript: Transcript, turns: List<Turn>): List<Transcript.Segment> {
        if (turns.isEmpty()) return transcript.segments
        if (transcript.words.isEmpty()) {
            // Engine without word timing: label whole segments.
            return transcript.segments.map { it.copy(speaker = speakerFor(it.startMs, it.endMs, turns)) }
        }

        val labelled = ArrayList<Pair<Transcript.Word, Int?>>()
        var previous: Int? = null
        for (w in transcript.words) {
            val s = speakerFor(w.startMs, w.endMs, turns) ?: previous
            labelled += w to s
            if (s != null) previous = s
        }
        // Words before the first labelled one take the first known speaker.
        val first = labelled.firstNotNullOfOrNull { it.second }

        val out = ArrayList<Transcript.Segment>()
        var words = ArrayList<Transcript.Word>()
        var speaker: Int? = null
        fun flush() {
            if (words.isEmpty()) return
            val text = words.joinToString(" ") { it.text }.replace(SPACE_BEFORE_PUNCT, "$1").trim()
            if (text.isNotEmpty()) out += Transcript.Segment(words.first().startMs, words.last().endMs, text, speaker)
            words = ArrayList()
        }
        for ((w, s0) in labelled) {
            val s = s0 ?: first
            val gap = if (words.isEmpty()) 0L else w.startMs - words.last().endMs
            if (words.isNotEmpty() && (s != speaker || gap > PAUSE_MS)) flush()
            if (words.isEmpty()) speaker = s
            words += w
        }
        flush()
        return out
    }

    /** Speaker with the most overlap with [from, to]; else the nearest turn if close; else null. */
    fun speakerFor(from: Long, to: Long, turns: List<Turn>): Int? {
        val end = maxOf(to, from + 1)
        val overlap = HashMap<Int, Long>()
        for (t in turns) {
            val o = minOf(end, t.endMs) - maxOf(from, t.startMs)
            if (o > 0) overlap[t.speaker] = (overlap[t.speaker] ?: 0) + o
        }
        overlap.maxByOrNull { it.value }?.let { return it.key }
        val mid = (from + end) / 2
        val nearest = turns.minByOrNull { t ->
            when {
                mid < t.startMs -> t.startMs - mid
                mid > t.endMs -> mid - t.endMs
                else -> 0
            }
        } ?: return null
        val d = if (mid < nearest.startMs) nearest.startMs - mid else mid - nearest.endMs
        return if (d <= NEAREST_MS) nearest.speaker else null
    }

    private val SPACE_BEFORE_PUNCT = Regex("\\s+([,.?!:;])")
}
