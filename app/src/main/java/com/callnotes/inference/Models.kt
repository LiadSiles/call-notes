package com.callnotes.inference

import java.io.File

/*
 * The two swappable "sockets": the processing workers only see these interfaces, so a
 * model can be replaced without touching the pipeline.
 * Transcriber: ivrit.ai Hebrew Whisper via whisper.cpp ([com.callnotes.inference.whisper]).
 * Analyzer: Gemma 4 via LiteRT-LM ([GemmaAnalyzer]); the prompt and the checks on its
 * answer live in [AnalysisPrompt].
 */

/** Audio -> timestamped text. */
interface Transcriber : AutoCloseable {
    val modelId: String

    /** Loads the model up front (slow: seconds to a minute). Optional; transcribe() loads lazily. */
    suspend fun load() {}

    /** Stops the current transcription as soon as the engine allows (any thread). */
    fun abort() {}

    /**
     * [audio] is a 16 kHz mono 16-bit WAV (the recorder's format; anything else is
     * converted first). [vocabulary]: names and words to expect, written as they should
     * come out ([com.callnotes.inference.whisper.WhisperPrompt]); "" for none.
     * [onProgress] reports (done, total).
     */
    suspend fun transcribe(
        audio: File,
        languageHint: String = "he",
        vocabulary: String = "",
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Transcript
}

/** A language model that answers prompts (the summary prompts are built by [CallSummarizer]). */
interface Analyzer : AutoCloseable {
    val modelId: String

    /** Loads the model up front (slow: seconds). Optional; [ask] loads lazily. */
    suspend fun load() {}

    /** The model's full answer; [onChars] reports how much has been written so far. */
    suspend fun ask(prompt: String, onChars: (Int) -> Unit = {}): String = conversation { it.ask(prompt, onChars) }

    /**
     * Several questions in one conversation: each answer sees the earlier questions and
     * answers. The model keeps what it has read, so a long transcript sent in the first
     * message is read once, not once per question.
     */
    suspend fun <T> conversation(block: suspend (Chat) -> T): T

    /** One open conversation (see [conversation]). */
    interface Chat {
        suspend fun ask(prompt: String, onChars: (Int) -> Unit = {}): String
    }

    /** Stops the current answer as soon as the engine allows (any thread). */
    fun abort() {}
}

data class Transcript(
    val segments: List<Segment>,
    /** Word timing when the engine provides it (Whisper); used to split speakers by word. */
    val words: List<Word> = emptyList(),
) {
    /**
     * [speaker]: 0/1 after speaker separation, null before / without it. [background]:
     * speech of someone not on the call ([com.callnotes.inference.speakers.BackgroundSpeech]).
     */
    data class Segment(
        val startMs: Long,
        val endMs: Long,
        val text: String,
        val speaker: Int? = null,
        val background: Boolean = false,
    )
    data class Word(val startMs: Long, val endMs: Long, val text: String)
}

/** What the analyzer knows about the call besides the words. */
data class CallContext(
    val startedAt: Long,
    val incoming: Boolean,
    val contactName: String?,
    /** False for internet calls: the app can't tell who called whom. */
    val directionKnown: Boolean = true,
    /** The phone owner's name, as set in settings, or null. */
    val ownerName: String? = null,
    /** Names and words that come up in the owner's calls (settings), spelled right. */
    val vocabulary: List<String> = emptyList(),
)

/** What the summariser extracted from a call (JSON from the model, checked in code). */
data class CallAnalysis(
    val title: String,
    val summary: String,
    val keyPoints: List<Point>,
    val decisions: List<Point>,
    val tasks: List<Task>,
    val followUps: List<Point>,
    /** False if the model said the transcript was too unclear to summarise reliably. */
    val summaryCertain: Boolean = true,
) {
    /**
     * One extracted item. [lines]: 1-based transcript lines it comes from (= segment idx + 1).
     * [certain] is false if the model wasn't sure or the app couldn't verify it.
     */
    data class Point(val text: String, val lines: List<Int> = emptyList(), val certain: Boolean = true)

    data class Task(
        val title: String,
        /** "me", "other" or "unknown". */
        val owner: String,
        val duePhrase: String?,
        /** ISO date, recomputed in code relative to the call time. */
        val dueDate: String?,
        val sourceQuote: String?,
        val lines: List<Int> = emptyList(),
        val certain: Boolean = true,
        /** "HH:mm" when a time of day was said, else null. Only kept with a [dueDate]. */
        val dueTime: String? = null,
    )
}

enum class ModelRole {
    TRANSCRIBE,
    ANALYZE,
    /** Voice fingerprint model (which voice is which). */
    SPEAKER_EMBEDDING,
}

/** Runtime that executes a model. (Not to be confused with LiteRT-LM's `Engine` class.) */
enum class InferenceEngine { LITERT_LM, WHISPER_CPP, SHERPA_ONNX }

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

    /**
     * ivrit.ai's Hebrew fine-tune of Whisper large-v3-turbo, ggml (f16) for whisper.cpp.
     * Pinned to commit 2130c78 (2025-05-22), Apache-2.0, not gated; checked 2026-10-21.
     */
    val WHISPER_HE_TURBO = ModelSpec(
        id = "whisper-he-turbo",
        displayName = "Whisper עברית (ivrit.ai)",
        roles = setOf(ModelRole.TRANSCRIBE),
        engine = InferenceEngine.WHISPER_CPP,
        fileName = "ivrit-whisper-large-v3-turbo-f16.bin",
        downloadUrl = "https://huggingface.co/ivrit-ai/whisper-large-v3-turbo-ggml/resolve/" +
            "2130c78e4a9cb4914cc4df91a1c3031407789705/ggml-model.bin",
        sizeBytes = 1_624_555_275,
        sha256 = "c8090411113357097bfafc2b8e228ec1639fa7f5fe4ecb5d054ac0ccef8641b1",
        minRamGb = 3.5,
    )

    // Pinned 2026-10-20 from the Hugging Face API (repos are not gated, Apache-2.0).
    // Gemma's own transcription was poor in Hebrew (0.3.x); it now does summaries/tasks.
    val GEMMA_4_E4B = ModelSpec(
        id = "gemma-4-e4b",
        displayName = "Gemma 4 E4B",
        roles = setOf(ModelRole.ANALYZE),
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
        roles = setOf(ModelRole.ANALYZE),
        engine = InferenceEngine.LITERT_LM,
        fileName = "gemma-4-E2B-it.litertlm",
        downloadUrl = "$HF/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
        sizeBytes = 2_588_147_712,
        sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
        minRamGb = 5.0,
    )

    /** WeSpeaker ResNet34-LM (VoxCeleb, CC-BY-4.0) — the embedding pyannote's own pipeline uses. */
    val WESPEAKER_RESNET34 = ModelSpec(
        id = "wespeaker-resnet34-lm",
        displayName = "WeSpeaker ResNet34",
        roles = setOf(ModelRole.SPEAKER_EMBEDDING),
        engine = InferenceEngine.SHERPA_ONNX,
        fileName = "wespeaker-en-voxceleb-resnet34-lm.onnx",
        downloadUrl = "https://huggingface.co/csukuangfj/speaker-embedding-models/resolve/" +
            "0743f301363dec56491a490f6d6cbc9d67f9a3bf/wespeaker_en_voxceleb_resnet34_LM.onnx",
        sizeBytes = 26_530_550,
        sha256 = "e9848563da86f263117134dfd7ad63c92355b37de492b55e325400c9d9c39012",
        minRamGb = 2.0,
    )

    /**
     * What speaker separation downloads. Only the voice-fingerprint model since 0.5.3:
     * pyannote segmentation went with sherpa's diarization pipeline (it could kill the app).
     */
    val SPEAKER_PACK = listOf(WESPEAKER_RESNET34)

    /** Files of models no longer used (pyannote segmentation, ≤ 0.5.2); deleted on start. */
    val RETIRED_FILES = listOf("pyannote-segmentation-3.0.onnx")

    /** Best first within each role. */
    val all = listOf(WHISPER_HE_TURBO, GEMMA_4_E4B, GEMMA_4_E2B, WESPEAKER_RESNET34)

    fun byId(id: String?): ModelSpec? = all.firstOrNull { it.id == id }

    fun forRole(role: ModelRole): List<ModelSpec> = all.filter { role in it.roles }
}
