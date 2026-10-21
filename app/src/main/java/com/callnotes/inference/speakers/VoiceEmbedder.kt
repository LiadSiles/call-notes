package com.callnotes.inference.speakers

import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.File

/**
 * Voice fingerprints (WeSpeaker ResNet34 through sherpa-onnx's embedding extractor).
 * This is the only sherpa-onnx component the app uses: its diarization pipeline calls
 * `exit(-1)` on edge cases and killed the app (0.5.1). Audio is checked for length and
 * `isReady` before `compute`, so the native side never meets input it rejects.
 */
class VoiceEmbedder(model: File, threads: Int) : AutoCloseable {

    private val extractor = SpeakerEmbeddingExtractor(
        config = SpeakerEmbeddingExtractorConfig(model = model.absolutePath, numThreads = threads),
    )

    /** Fingerprint of 16 kHz mono [samples], or null if too short to be reliable. */
    fun embed(samples: FloatArray): FloatArray? {
        if (samples.size < MIN_SAMPLES) return null
        val stream = extractor.createStream()
        try {
            stream.acceptWaveform(samples, 16_000)
            stream.inputFinished()
            if (!extractor.isReady(stream)) return null
            return extractor.compute(stream)
        } finally {
            stream.release()
        }
    }

    override fun close() = extractor.release()

    companion object {
        /** 1 s at 16 kHz. */
        const val MIN_SAMPLES = 16_000
    }
}
