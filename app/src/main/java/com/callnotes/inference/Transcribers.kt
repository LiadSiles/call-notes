package com.callnotes.inference

import android.content.Context
import com.callnotes.inference.whisper.WhisperTranscriber
import java.io.File

/** Picks the implementation for a catalog entry. */
object Transcribers {
    fun create(context: Context, spec: ModelSpec, file: File): Transcriber = when (spec.engine) {
        InferenceEngine.WHISPER_CPP -> WhisperTranscriber(spec, file)
        InferenceEngine.LITERT_LM -> GemmaTranscriber(context, spec, file)
        InferenceEngine.LLAMA_CPP, InferenceEngine.SHERPA_ONNX -> error("${spec.id} is not a transcriber")
    }
}
