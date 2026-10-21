package com.callnotes.inference.speakers

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Voice fingerprints (speaker embeddings): similarity and compact storage. */
object Voice {

    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return if (na == 0.0 || nb == 0.0) 0f else (dot / (sqrt(na) * sqrt(nb))).toFloat()
    }

    /** Speakers 0 and 1, little-endian floats; a missing speaker is stored as zeros. */
    fun pack(embeddings: Map<Int, FloatArray>): ByteArray? {
        val dim = embeddings.values.firstOrNull()?.size ?: return null
        val buf = ByteBuffer.allocate(2 * dim * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (s in 0..1) {
            val e = embeddings[s] ?: FloatArray(dim)
            e.forEach { buf.putFloat(it) }
        }
        return buf.array()
    }

    fun unpack(bytes: ByteArray?): Map<Int, FloatArray> {
        if (bytes == null || bytes.isEmpty() || bytes.size % 8 != 0) return emptyMap()
        val dim = bytes.size / 8
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return (0..1).mapNotNull { s ->
            val e = FloatArray(dim) { buf.float }
            if (e.all { it == 0f }) null else s to e
        }.toMap()
    }

    /**
     * Which speaker is the owner, given their voice sample: the closer one, if it is
     * close enough and clearly closer than the other. Null if undecidable.
     */
    fun whichIsMe(owner: FloatArray, speakers: Map<Int, FloatArray>): Int? {
        val scores = speakers.mapValues { cosine(owner, it.value) }
        val best = scores.maxByOrNull { it.value } ?: return null
        val other = scores.filterKeys { it != best.key }.values.maxOrNull()
        if (best.value < MIN_MATCH) return null
        if (other != null && best.value - other < MIN_MARGIN) return null
        return best.key
    }

    const val MIN_MATCH = 0.35f
    const val MIN_MARGIN = 0.10f
}
