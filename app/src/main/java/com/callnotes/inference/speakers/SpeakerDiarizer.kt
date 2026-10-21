package com.callnotes.inference.speakers

import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.File

/**
 * Who spoke when, on the device: pyannote segmentation-3.0 finds speech and speaker
 * changes, WeSpeaker embeddings tell the voices apart, and clustering is fixed to two
 * speakers (a phone call). Runs through sherpa-onnx (onnxruntime, CPU).
 */
class SpeakerDiarizer(segmentation: File, embedding: File, threads: Int) : AutoCloseable {

    data class Result(
        val turns: List<SpeakerAligner.Turn>,
        /** Voice fingerprint per speaker index (missing if it spoke too little). */
        val embeddings: Map<Int, FloatArray>,
        /** True if both "speakers" turned out to be the same voice and were merged. */
        val merged: Boolean,
    )

    private val diarization = OfflineSpeakerDiarization(
        config = OfflineSpeakerDiarizationConfig(
            segmentation = OfflineSpeakerSegmentationModelConfig(
                pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(segmentation.absolutePath),
                numThreads = threads,
            ),
            embedding = SpeakerEmbeddingExtractorConfig(model = embedding.absolutePath, numThreads = threads),
            clustering = FastClusteringConfig(numClusters = 2),
            minDurationOn = 0.2f,
            minDurationOff = 0.4f,
        ),
    )
    private val extractor = SpeakerEmbeddingExtractor(
        config = SpeakerEmbeddingExtractorConfig(model = embedding.absolutePath, numThreads = threads),
    )

    /**
     * [samples]: 16 kHz mono in [-1, 1].
     *
     * Uses `process`, not `processWithCallback`: sherpa's JNI looks the callback up as a
     * method `invoke(IIJ)Ljava/lang/Integer;` on the lambda's class, which only old-style
     * class-compiled Kotlin lambdas have. Kotlin 2 compiles lambdas with invokedynamic, the
     * lookup fails and ART aborts the whole app (0.4.1–0.5.0 crash on the POCO).
     */
    fun diarize(samples: FloatArray): Result {
        val segments = diarization.process(samples)
        var turns = segments.map {
            SpeakerAligner.Turn((it.start * 1000).toLong(), (it.end * 1000).toLong(), it.speaker)
        }.sortedBy { it.startMs }

        val embeddings = turns.map { it.speaker }.distinct().mapNotNull { s ->
            embedTurns(samples, turns.filter { it.speaker == s })?.let { s to it }
        }.toMap()

        // Forced to two clusters, one voice can still come out as two "speakers" (e.g. the
        // far side wasn't captured). Very similar fingerprints → one speaker.
        val a = embeddings[0]; val b = embeddings[1]
        val merged = a != null && b != null && Voice.cosine(a, b) >= SAME_VOICE
        if (merged) turns = turns.map { it.copy(speaker = 0) }
        return Result(turns, if (merged) mapOf(0 to a!!) else embeddings, merged)
    }

    /** Fingerprint of a voice from its longest turns (up to ~20 s), or null if under 1 s. */
    private fun embedTurns(samples: FloatArray, turns: List<SpeakerAligner.Turn>): FloatArray? {
        val picked = ArrayList<FloatArray>()
        var total = 0
        for (t in turns.sortedByDescending { it.endMs - it.startMs }) {
            val from = (t.startMs * 16).toInt().coerceIn(0, samples.size)
            val to = (t.endMs * 16).toInt().coerceIn(from, samples.size)
            if (to - from < 8_000) continue // < 0.5 s: too short to be reliable
            picked += samples.copyOfRange(from, to)
            total += to - from
            if (total >= 20 * 16_000) break
        }
        if (total < 16_000) return null
        val joined = FloatArray(total)
        var at = 0
        for (p in picked) { p.copyInto(joined, at); at += p.size }
        return embed(joined)
    }

    /** Voice fingerprint of [samples] (16 kHz mono). Also used for the owner's voice sample. */
    fun embed(samples: FloatArray): FloatArray {
        val stream = extractor.createStream()
        try {
            stream.acceptWaveform(samples, 16_000)
            stream.inputFinished()
            check(extractor.isReady(stream)) { "audio too short for a voice fingerprint" }
            return extractor.compute(stream)
        } finally {
            stream.release()
        }
    }

    override fun close() {
        diarization.release()
        extractor.release()
    }

    companion object {
        /** Cosine above which two fingerprints are treated as the same voice. */
        const val SAME_VOICE = 0.75f

        /** Fingerprint of one voice sample, without loading the segmentation model. */
        fun embedOnly(embedding: File, samples: FloatArray, threads: Int): FloatArray {
            val extractor = SpeakerEmbeddingExtractor(
                config = SpeakerEmbeddingExtractorConfig(model = embedding.absolutePath, numThreads = threads),
            )
            try {
                val stream = extractor.createStream()
                try {
                    stream.acceptWaveform(samples, 16_000)
                    stream.inputFinished()
                    check(extractor.isReady(stream)) { "audio too short for a voice fingerprint" }
                    return extractor.compute(stream)
                } finally {
                    stream.release()
                }
            } finally {
                extractor.release()
            }
        }
    }
}
