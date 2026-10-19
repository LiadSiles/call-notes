package com.callnotes.inference

import java.io.File

/*
 * The two swappable "sockets" from the plan. Nothing implements them yet — the
 * next milestone adds Gemma 4 through LiteRT-LM. Any model the user picks plugs
 * into one (or both) of these, so the rest of the app never knows which model ran.
 */

/** Audio -> timestamped text. */
interface Transcriber {
    val modelId: String

    /** [audio] is a 16 kHz mono WAV, as written by the recorder. */
    suspend fun transcribe(audio: File, languageHint: String = "he"): Transcript
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

enum class Engine { LITERT_LM, LLAMA_CPP, WHISPER_CPP }

/** One entry in the downloadable model catalog. */
data class ModelSpec(
    val id: String,
    val displayName: String,
    val roles: Set<ModelRole>,
    val engine: Engine,
    val downloadUrl: String,
    val sizeBytes: Long?,
    val sha256: String?,
    val minRamGb: Int,
)

object ModelCatalog {
    // Download URLs, sizes and checksums get pinned when the Gemma integration lands.
    val defaults = listOf(
        ModelSpec(
            id = "gemma-4-e4b",
            displayName = "Gemma 4 E4B",
            roles = setOf(ModelRole.TRANSCRIBE, ModelRole.ANALYZE),
            engine = Engine.LITERT_LM,
            downloadUrl = "TODO",
            sizeBytes = null,
            sha256 = null,
            minRamGb = 8,
        ),
        ModelSpec(
            id = "gemma-4-e2b",
            displayName = "Gemma 4 E2B",
            roles = setOf(ModelRole.TRANSCRIBE, ModelRole.ANALYZE),
            engine = Engine.LITERT_LM,
            downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm",
            sizeBytes = null,
            sha256 = null,
            minRamGb = 6,
        ),
    )
}
