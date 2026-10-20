package com.callnotes.inference

import java.io.File

/*
 * The two swappable "sockets" from the plan. Any model the user picks plugs into one
 * (or both) of these, so the rest of the app never knows which model ran.
 * 0.3 implements Transcriber with Gemma 4 (GemmaTranscriber); Analyzer comes in 0.4.
 */

/** Audio -> timestamped text. */
interface Transcriber : AutoCloseable {
    val modelId: String

    /**
     * [audio] is a 16 kHz mono 16-bit WAV (the recorder's format; imports are converted
     * first). [onProgress] reports (chunks done, chunks total).
     */
    suspend fun transcribe(
        audio: File,
        languageHint: String = "he",
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Transcript
}

/** Transcript -> structured summary, tasks and dates. */
interface Analyzer {
    val modelId: String
    suspend fun analyze(transcript: Transcript, call: CallContext): CallAnalysis
}

data class Transcript(val segments: List<Segment>) {
    data class Segment(val startMs: Long, val endMs: Long, val text: String)

    val text: String get() = segments.joinToString(" ") { it.text }
}

/** What the analyzer knows about the call besides the words. */
data class CallContext(
    val startedAt: Long,
    val incoming: Boolean,
    val contactName: String?,
)

/** Mirrors the JSON schema in the plan. */
data class CallAnalysis(
    val title: String,
    val summary: String,
    val keyPoints: List<String>,
    val decisions: List<String>,
    val tasks: List<Task>,
    val followUps: List<String>,
    val language: String,
) {
    data class Task(
        val title: String,
        /** "me" or "other". */
        val owner: String,
        val duePhrase: String?,
        /** ISO date, recomputed in code relative to the call time. */
        val dueDate: String?,
        val confidence: Float,
        val sourceQuote: String?,
    )
}

enum class ModelRole { TRANSCRIBE, ANALYZE }

/** Runtime that executes a model. (Not to be confused with LiteRT-LM's `Engine` class.) */
enum class InferenceEngine { LITERT_LM, LLAMA_CPP, WHISPER_CPP }

/** One entry in the downloadable model catalog. */
data class ModelSpec(
    val id: String,
    val displayName: String,
    val roles: Set<ModelRole>,
    val engine: InferenceEngine,
    val fileName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val sha256: String,
    /** Minimum total RAM the device should report, in GB (devices report a bit under nominal). */
    val minRamGb: Double,
)

object ModelCatalog {
    private const val HF = "https://huggingface.co/litert-community"

    // Pinned 2026-10-20 from the Hugging Face API (repos are not gated, Apache-2.0).
    val GEMMA_4_E4B = ModelSpec(
        id = "gemma-4-e4b",
        displayName = "Gemma 4 E4B",
        roles = setOf(ModelRole.TRANSCRIBE, ModelRole.ANALYZE),
        engine = InferenceEngine.LITERT_LM,
        fileName = "gemma-4-E4B-it.litertlm",
        downloadUrl = "$HF/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
        sizeBytes = 3_659_530_240,
        sha256 = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0",
        minRamGb = 7.0,
    )

    val GEMMA_4_E2B = ModelSpec(
        id = "gemma-4-e2b",
        displayName = "Gemma 4 E2B",
        roles = setOf(ModelRole.TRANSCRIBE, ModelRole.ANALYZE),
        engine = InferenceEngine.LITERT_LM,
        fileName = "gemma-4-E2B-it.litertlm",
        downloadUrl = "$HF/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
        sizeBytes = 2_588_147_712,
        sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
        minRamGb = 5.0,
    )

    /** Best first. */
    val all = listOf(GEMMA_4_E4B, GEMMA_4_E2B)

    fun byId(id: String?): ModelSpec? = all.firstOrNull { it.id == id }
}
