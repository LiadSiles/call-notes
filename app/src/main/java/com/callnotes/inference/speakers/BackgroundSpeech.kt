package com.callnotes.inference.speakers

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Finds speech that is in the recording but not part of the call: people talking in the
 * background, on the owner's side or on the other side. Such speech is set aside (marked,
 * hidden by default, left out of the summary) — never deleted. Pure (unit-tested).
 *
 * Works on the same ≤ 3 s windows used for voices, per side of the call (from the speaker
 * separation):
 *
 * 1. **The voices of each side** are the voices heard at that side's speaking level that
 *    make up a real share of it ([MAJOR_SHARE]) — one person, or two when the other end is
 *    on speakerphone — plus the owner's voice sample on the owner's side.
 * 2. **A foreign window** has a voice unlike every voice of both sides (so a quiet word of
 *    the owner, or an owner's word the separation put on the wrong side, is never foreign).
 * 3. A foreign window is background when it is **quiet for its side** ([QUIET_DB] below the
 *    side's speaking level — someone farther from the microphone) and **not answered**: a
 *    quiet voice the other side keeps replying to is taking part (someone across the room
 *    joining in), so it stays. A foreign voice at full level is set aside only when it is a
 *    small share of its side and nobody ever answered it.
 * 4. Too short to fingerprint → follows the window next to it.
 *
 * Safety: nothing is set aside when the sides aren't known, without voice fingerprints, or
 * when the result would be more than [MAX_SHARE] of the speech — that points at a wrong
 * estimate, not at a noisy room.
 */
object BackgroundSpeech {

    /** How much quieter than its side's speaking level a window must be. */
    const val QUIET_DB = 10.0
    /** Fingerprints at least this similar are the same person (conservative: keeps more). */
    const val SAME_PERSON = 0.40f
    /** A voice with this share of its side's speech (at level) is one of the side's people. */
    private const val MAJOR_SHARE = 0.15
    /** A foreign voice at full level is set aside only if this small a share of its side. */
    private const val MINOR_SHARE = 0.10
    /** Quiet windows of one background voice are grouped at this similarity. */
    private const val SAME_GROUP = 0.50f
    /** An answer starts this soon after a window ends. */
    private const val ANSWER_MS = 1_500L
    /** A quiet voice answered this often is taking part. */
    private const val ANSWERED_SHARE = 0.34
    private const val MIN_ANSWERED = 2
    /** More background than this means the estimate is off: set nothing aside. */
    const val MAX_SHARE = 0.35
    /** Windows per voice kept to compare against (the longest). */
    private const val MAX_EXEMPLARS = 40

    data class Result(
        /** Per window: set aside as background. */
        val background: BooleanArray,
        val backgroundMs: Long,
        val speechMs: Long,
        /** Quiet voices kept because the other side answered them. */
        val keptAnswered: Int,
        /** Why nothing was set aside, if so. */
        val skipped: String? = null,
    ) {
        val count get() = background.count { it }
    }

    /** The side's speaking level: a loud percentile (by duration) of its windows' levels. */
    fun sideLevel(levels: List<Double>, durations: List<Long>): Double? {
        val pairs = levels.zip(durations).filter { it.first > -80 }.sortedBy { it.first }
        if (pairs.isEmpty()) return null
        val total = pairs.sumOf { it.second }
        var acc = 0L
        for ((l, d) in pairs) {
            acc += d
            if (acc >= total * 0.75) return l
        }
        return pairs.last().first
    }

    /**
     * [sides]: 0/1 per window from the speaker separation. [levels]: dBFS per window of
     * the original recording. [embeddings]: per window, null where not computed or too
     * short. [ownerSide] / [ownerVoice]: the owner's side and voice sample, if known.
     */
    fun find(
        windows: List<VoiceClusters.Window>,
        sides: IntArray,
        levels: DoubleArray,
        embeddings: List<FloatArray?>,
        ownerSide: Int? = null,
        ownerVoice: FloatArray? = null,
    ): Result {
        val n = windows.size
        val speechMs = windows.sumOf { it.durationMs }
        val none = BooleanArray(n)
        if (n == 0 || embeddings.all { it == null }) return Result(none, 0, speechMs, 0, "no voice fingerprints")
        if (sides.distinct().size < 2) return Result(none, 0, speechMs, 0, "sides of the call not known")

        // 1. Each side's level and voices.
        val refs = HashMap<Int, Double>()
        val exemplars = ArrayList<FloatArray>()
        val sideMs = HashMap<Int, Long>()
        for (s in 0..1) {
            val idx = windows.indices.filter { sides[it] == s }
            sideMs[s] = idx.sumOf { windows[it].durationMs }
            val ref = sideLevel(idx.map { levels[it] }, idx.map { windows[it].durationMs }) ?: continue
            refs[s] = ref
            val atLevel = idx.filter { levels[it] > ref - QUIET_DB && embeddings[it] != null }
            val total = atLevel.sumOf { windows[it].durationMs }.coerceAtLeast(1)
            val groups = groupsByVoice(atLevel, embeddings).sortedByDescending { g -> g.sumOf { windows[it].durationMs } }
            groups.forEachIndexed { k, g ->
                val ms = g.sumOf { windows[it].durationMs }
                if (k == 0 || ms >= total * MAJOR_SHARE) {
                    exemplars += g.sortedByDescending { windows[it].durationMs }.take(MAX_EXEMPLARS).map { embeddings[it]!! }
                }
            }
            if (s == ownerSide && ownerVoice != null) exemplars += ownerVoice
        }
        if (exemplars.isEmpty()) return Result(none, 0, speechMs, 0, "no clear voices")

        // 2. Foreign windows: unlike every voice of the call.
        val foreign = BooleanArray(n) { i ->
            val e = embeddings[i]
            e != null && levels[i] > -80 && exemplars.maxOf { Voice.cosine(e, it) } < SAME_PERSON
        }
        // 4. Too short to fingerprint: like the nearest fingerprinted window on its side, if within a second.
        for (i in 0 until n) {
            if (embeddings[i] != null || levels[i] <= -80) continue
            val near = listOf(i - 1, i + 1).filter { it in 0 until n && sides[it] == sides[i] && embeddings[it] != null }
                .minByOrNull { gapMs(windows[i], windows[it]) } ?: continue
            if (gapMs(windows[i], windows[near]) <= 1_000 && foreign[near]) foreign[i] = true
        }
        fun quiet(i: Int) = refs[sides[i]]?.let { levels[i] <= it - QUIET_DB } == true

        // 3. Quiet and not answered → background; full level only if a small, never-answered voice.
        val candidate = BooleanArray(n)
        var keptAnswered = 0
        for (g in groupsByVoice(foreign.indices.filter { foreign[it] }, embeddings)) {
            val answered = g.count { answered(it, windows, sides) }
            val takingPart = answered >= MIN_ANSWERED && answered >= g.size * ANSWERED_SHARE
            if (takingPart) { keptAnswered++; continue }
            val loud = g.filter { !quiet(it) }
            val loudMs = loud.sumOf { windows[it].durationMs }
            val side = sides[g.first()]
            val minorAndIgnored = answered == 0 && loudMs <= (sideMs[side] ?: 0) * MINOR_SHARE
            for (i in g) if (quiet(i) || minorAndIgnored) candidate[i] = true
        }

        val backgroundMs = windows.indices.filter { candidate[it] }.sumOf { windows[it].durationMs }
        if (speechMs > 0 && backgroundMs > speechMs * MAX_SHARE) {
            return Result(none, 0, speechMs, keptAnswered, "would set aside ${backgroundMs * 100 / speechMs}% of the speech")
        }
        return Result(candidate, backgroundMs, speechMs, keptAnswered)
    }

    /** The other side starts speaking within [ANSWER_MS] after window [i] ends. */
    private fun answered(i: Int, windows: List<VoiceClusters.Window>, sides: IntArray): Boolean {
        val end = windows[i].endMs
        for (j in i + 1 until windows.size) {
            val w = windows[j]
            if (w.startMs > end + ANSWER_MS) return false
            if (sides[j] != sides[i] && w.startMs >= end - 200) return true
        }
        return false
    }

    /** Single-link groups of windows whose fingerprints are alike; unfingerprinted ones join the nearest group. */
    private fun groupsByVoice(idx: List<Int>, embeddings: List<FloatArray?>): List<List<Int>> {
        val withVoice = idx.filter { embeddings[it] != null }
        val group = IntArray(embeddings.size) { -1 }
        var next = 0
        for (i in withVoice) {
            if (group[i] >= 0) continue
            group[i] = next
            val queue = ArrayDeque(listOf(i))
            while (queue.isNotEmpty()) {
                val a = queue.removeFirst()
                for (b in withVoice) {
                    if (group[b] < 0 && Voice.cosine(embeddings[a]!!, embeddings[b]!!) >= SAME_GROUP) {
                        group[b] = group[a]; queue.addLast(b)
                    }
                }
            }
            next++
        }
        for (i in idx) if (group[i] < 0) {
            val near = withVoice.minByOrNull { kotlin.math.abs(it - i) }
            group[i] = near?.let { group[it] } ?: next++
        }
        return idx.groupBy { group[it] }.values.toList()
    }

    private fun gapMs(a: VoiceClusters.Window, b: VoiceClusters.Window): Long =
        max(0L, max(a.startMs, b.startMs) - minOf(a.endMs, b.endMs))

    /** RMS level of a window of the recording, dBFS. */
    fun levelDb(samples: FloatArray, w: VoiceClusters.Window, sampleRate: Int = 16_000): Double {
        val perMs = sampleRate / 1000
        val from = (w.startMs * perMs).toInt().coerceIn(0, samples.size)
        val to = (w.endMs * perMs).toInt().coerceIn(from, samples.size)
        if (to - from < perMs * 20) return -100.0
        var sum = 0.0
        for (i in from until to) sum += samples[i].toDouble() * samples[i]
        val rms = sqrt(sum / (to - from))
        return if (rms <= 1e-6) -100.0 else 20 * log10(rms)
    }

    /** Time ranges of the background windows, for [SpeakerAligner.align]. */
    fun ranges(windows: List<VoiceClusters.Window>, result: Result): List<LongRange> =
        windows.indices.filter { result.background[it] }.map { windows[it].startMs..windows[it].endMs }
}
