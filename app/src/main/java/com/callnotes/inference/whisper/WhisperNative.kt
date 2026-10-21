package com.callnotes.inference.whisper

import java.io.File

/**
 * JNI surface of `libcallnotes_whisper.so` (app/src/main/cpp). The library is built for
 * ARMv8.2-A with fp16 + dot product, so [isSupported] must be checked before [load];
 * loading it on an older core would crash with an illegal instruction.
 */
object WhisperNative {

    /** `whisper_alignment_heads_preset` value for large-v3-turbo (DTW word timing). */
    const val AHEADS_LARGE_V3_TURBO = 14

    fun interface ProgressListener {
        fun onProgress(percent: Int)
    }

    @Volatile private var loaded = false

    /** CPU has the instructions the native build uses (fp16 arithmetic + dot product). */
    fun isSupported(): Boolean {
        val features = runCatching {
            File("/proc/cpuinfo").readLines()
                .filter { it.startsWith("Features") }
                .flatMap { it.substringAfter(':').trim().split(' ') }
                .toSet()
        }.getOrDefault(emptySet())
        return "asimddp" in features && "fphp" in features && "asimdhp" in features
    }

    @Synchronized
    fun load() {
        if (loaded) return
        check(isSupported()) { "unsupported CPU (needs ARMv8.2 fp16 + dotprod)" }
        System.loadLibrary("callnotes_whisper")
        loaded = true
    }

    external fun init(modelPath: String, dtw: Boolean, aheadsPreset: Int): Long
    external fun free(handle: Long)
    external fun abort(handle: Long)
    external fun systemInfo(): String
    external fun transcribe(
        handle: Long,
        samples: FloatArray,
        language: String,
        threads: Int,
        beamSize: Int,
        listener: ProgressListener?,
    ): Array<Any>?
}
