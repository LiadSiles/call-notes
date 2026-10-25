package com.callnotes.inference.speakers

import com.callnotes.inference.Transcript

/**
 * Who said each word, from two kinds of evidence at once instead of one or the other:
 *
 * - **Loudness** ([LevelSpeakers.Model]): on a call held to the ear the owner is ~10–30 dB
 *   louder than the other side leaking from the earpiece.
 * - **Voice** (a fingerprint per word, [VoiceTrack]): on speakerphone and internet calls,
 *   where the levels are close.
 *
 * Each source counts in proportion to how clearly it separates the two speakers in this
 * call, and a two-state Viterbi pass ([TwoSpeakerViterbi]) joins them with pause-aware
 * switching ([LevelSpeakers.switchCost]). Until 0.18 the app used loudness *or* voice, and
 * voice on windows of up to 3 s grouped at pauses — a quick answer ended up inside the
 * other speaker's window. Pure (unit-tested).
 */
object SpeakerDecoder {

    /** Loudness counts from this separation (lower than [LevelSpeakers.MIN_SEPARATION_DB]: here it is one vote of two). */
    const val MIN_LEVEL_DB = 5.0
    /** Voices this alike are one voice. */
    const val SAME_VOICE = 0.75f
    /** Each speaker needs this share of the speech. */
    private const val MIN_SHARE = 0.05
    /** Scale of the voice evidence (cosine margin → log-odds), as in [VoiceClusters]. */
    private const val VOICE_SCALE = 8.0
    private const val MIN_WORDS = 12

    data class Result(
        /** Per word: speaker 0/1. 0 = the louder one when loudness took part. */
        val labels: IntArray,
        /** Loudness model, if it took part. */
        val level: LevelSpeakers.Model?,
        /** Cosine between the two voices, if voice took part. */
        val voiceSimilarity: Float?,
        /** Voice of each speaker (mean fingerprint of their words), when fingerprints exist. */
        val centroids: Map<Int, FloatArray>,
        val shareOf1: Double,
    )

    /**
     * [levels]: dBFS per word. [voices]: fingerprint per word (null = none). Null when
     * neither source separates two speakers.
     */
    fun decode(words: List<Transcript.Word>, levels: DoubleArray, voices: List<FloatArray?>): Result? {
        val n = words.size
        if (n < MIN_WORDS) return null
        val weights = DoubleArray(n) { maxOf(40.0, (words[it].endMs - words[it].startMs).toDouble()) }
        val gaps = LongArray(n) { i -> if (i == 0) 0 else words[i].startMs - words[i - 1].endMs }

        val level = LevelSpeakers.fit(levels, weights)?.takeIf { it.separationDb >= MIN_LEVEL_DB }
        val found = voiceClusters(voices, weights, level?.let { m -> IntArray(n) { if (levels[it] >= (m.loudDb + m.quietDb) / 2) 0 else 1 } })
        val similarity = found?.let { cos(it[0], it[1]) }
        val centroids = found?.takeIf { similarity!! < SAME_VOICE }
        if (level == null && centroids == null) return null

        // How much each source counts: by how clearly it separates the two in this call.
        val levelWeight = level?.let { ((it.separationDb - MIN_LEVEL_DB) / 5.0).coerceIn(0.3, 1.0) } ?: 0.0
        val voiceWeight = if (centroids != null) ((SAME_VOICE - similarity!!) / 0.35f).toDouble().coerceIn(0.3, 1.0) else 0.0
        val means = level?.let { doubleArrayOf(it.loudDb, it.quietDb) }
        val sd = level?.sd ?: 1.0

        val labels = TwoSpeakerViterbi.decode(
            n,
            emit = { i, s ->
                var e = 0.0
                if (means != null && levels[i] > -80) {
                    val z = (levels[i] - means[s]) / sd
                    e += levelWeight * -0.5 * z * z
                }
                val v = voices[i]
                if (centroids != null && v != null) {
                    e += voiceWeight * VOICE_SCALE * (cos(v, centroids[s]) - cos(v, centroids[1 - s]))
                }
                e
            },
            switchCost = { i -> LevelSpeakers.switchCost(gaps[i]) },
        )

        var t1 = 0.0; var total = 0.0
        for (i in 0 until n) { total += weights[i]; if (labels[i] == 1) t1 += weights[i] }
        val share = t1 / total
        if (share < MIN_SHARE || share > 1 - MIN_SHARE) return null

        // Each speaker's voice from the words finally given to them (for "me" and later re-labelling).
        val voiceOf = (0..1).mapNotNull { s ->
            val vs = voices.indices.filter { labels[it] == s && voices[it] != null }.map { voices[it]!! }
            if (vs.isEmpty()) null else s to VoiceTrack.unit(VoiceTrack.mean(vs))
        }.toMap()
        return Result(labels, level, if (centroids != null) similarity else null, voiceOf, share)
    }

    /**
     * Two voice centroids by weighted 2-means on the word fingerprints. Seeded from the
     * loudness split when there is one (so voice cluster 0 = the louder speaker), else from
     * the longest word and the word least like it. Null with too few fingerprints.
     */
    private fun voiceClusters(voices: List<FloatArray?>, weights: DoubleArray, levelSplit: IntArray?): Array<FloatArray>? {
        val idx = voices.indices.filter { voices[it] != null }
        if (idx.size < MIN_WORDS) return null
        var c: Array<FloatArray> = if (levelSplit != null && (0..1).all { s -> idx.any { levelSplit[it] == s } }) {
            Array(2) { s -> VoiceTrack.unit(VoiceTrack.mean(idx.filter { levelSplit[it] == s }.map { voices[it]!! })) }
        } else {
            val a = idx.maxBy { weights[it] }
            val b = idx.minBy { cos(voices[it]!!, voices[a]!!) }
            arrayOf(voices[a]!!, voices[b]!!)
        }
        val assign = IntArray(voices.size)
        repeat(15) {
            for (i in idx) assign[i] = if (cos(voices[i]!!, c[0]) >= cos(voices[i]!!, c[1])) 0 else 1
            c = Array(2) { s ->
                val members = idx.filter { assign[it] == s }
                if (members.isEmpty()) c[s] else VoiceTrack.unit(VoiceTrack.mean(members.map { voices[it]!! }))
            }
        }
        return c
    }

    /**
     * Which speaker is the owner. In order: a call clearly held to the ear (big loudness gap,
     * speaker off) → the louder one, whatever the voice sample says (the earpiece leak's
     * fingerprint is too distorted to overrule that); the owner's voice sample; a loudness
     * split with the speaker off → the louder one; whoever spoke first (whoever answers
     * speaks first: the owner on an incoming call, the other side on an outgoing one).
     * Null when nothing decides — the call then shows "דובר 1/2".
     */
    fun whoIsMe(
        result: Result,
        speakerOn: Boolean,
        voiceSays: Int?,
        firstSpeaker: Int?,
        incoming: Boolean?,
    ): Pair<Int?, String> {
        val gap = result.level?.separationDb
        return when {
            gap != null && !speakerOn && gap >= HANDSET_GAP_DB -> 0 to "louder (held to the ear)"
            voiceSays != null -> voiceSays to "voice sample"
            gap != null && !speakerOn && gap >= LevelSpeakers.MIN_SEPARATION_DB -> 0 to "louder"
            firstSpeaker != null && incoming != null ->
                (if (incoming) firstSpeaker else 1 - firstSpeaker) to "first to speak (${if (incoming) "incoming" else "outgoing"})"
            else -> null to "unknown"
        }
    }

    /** Level gap above which a call was certainly held to the ear (louder = owner). */
    const val HANDSET_GAP_DB = 15.0

    private fun cos(a: FloatArray, b: FloatArray): Float = Voice.cosine(a, b)
}
