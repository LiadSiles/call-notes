package com.callnotes.inference

import com.callnotes.inference.whisper.WhisperSpeed
import com.callnotes.inference.whisper.WhisperTranscriber
import java.io.File

/** Picks the implementation for a catalog entry. */
object Transcribers {
    /**
     * [speed] picks the Whisper preset: the model file it runs on (the downloaded one or a
     * quantized copy) and the decoding. A FAST preset whose copy couldn't be made runs the
     * full model with its greedy decoding.
     */
    fun create(spec: ModelSpec, file: File, speed: WhisperSpeed = WhisperSpeed.ACCURATE): Transcriber {
        check(spec.engine == InferenceEngine.WHISPER_CPP) { "${spec.id} is not a transcriber" }
        val copy = speed.fileFor(file)
        return if (speed.quantType == null || copy.isFile) WhisperTranscriber(spec, copy, speed.beamSize, speed.suffix)
        else WhisperTranscriber(spec, file, speed.beamSize)
    }
}
