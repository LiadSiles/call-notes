package com.callnotes.inference

import java.io.File

/*
 * The two swappable "sockets" from the plan. Any model the user picks plugs into one
 * (or both) of these, so the rest of the app never knows which model ran.
 * Transcriber: ivrit.ai Hebrew Whisper via whisper.cpp (0.4; Gemma 4 audio until 0.3.8).
 * Analyzer: Gemma 4 (next).
 */

/** Audio -> timestamped text. */
interface Transcriber : AutoCloseable {
    val modelId: String

    /** Loads the model up front (slow: seconds to a minute). Optional; transcribe() loads lazily. */
    suspend fun load() {}

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

data class Transcript(
    val segments: List<Segment>,
    /** Word timing when the engine provides it (Whisper); used to split speakers by word. */
    val words: List<Word> = emptyList(),
) {
    /** [speaker]: 0/1 after speaker separation, null before / without it. */
    data class Segment(val startMs: Long, val endMs: Long, val text: String, val speaker: Int? = null)
    data class Word(val startMs: Long, val endMs: Long, val text: String, val probability: Float = 1f)

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

enum class ModelRole {
    TRANSCRIBE,
    ANALYZE,
    /** pyannote segmentation (who speaks when). */
    SPEAKER_SEGMENTATION,
    /** Voice fingerprint model (which voice is which). */
    SPEAKER_EMBEDDING,
}

/** Runtime that executes a model. (Not to be confused with LiteRT-LM's `Engine` class.) */
enum class InferenceEngine { LITERT_LM, LLAMA_CPP, WHISPER_CPP, SHERPA_ONNX }

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

    /**
     * Speaker separation, run by sherpa-onnx v1.13.8 (k2-fsa). pyannote segmentation-3.0
     * exported to ONNX (MIT), from the sherpa-onnx author's HF mirror, pinned commit.
     */
    val PYANNOTE_SEGMENTATION = ModelSpec(
        id = "pyannote-segmentation-3.0",
        displayName = "pyannote segmentation 3.0",
        roles = setOf(ModelRole.SPEAKER_SEGMENTATION),
        engine = InferenceEngine.SHERPA_ONNX,
        fileName = "pyannote-segmentation-3.0.onnx",
        downloadUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-pyannote-segmentation-3-0/resolve/" +
            "9403a6902bb58e3d5ae8c7e77c3422de279db2e0/model.onnx",
        sizeBytes = 5_992_913,
        sha256 = "220ad67ca923bef2fa91f2390c786097bf305bceb5e261d4af67b38e938e1079",
        minRamGb = 2.0,
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

    /** Both files speaker separation needs; downloaded together. */
    val SPEAKER_PACK = listOf(PYANNOTE_SEGMENTATION, WESPEAKER_RESNET34)

    /** Best first within each role. */
    val all = listOf(WHISPER_HE_TURBO, GEMMA_4_E4B, GEMMA_4_E2B, PYANNOTE_SEGMENTATION, WESPEAKER_RESNET34)

    fun byId(id: String?): ModelSpec? = all.firstOrNull { it.id == id }

    fun forRole(role: ModelRole): List<ModelSpec> = all.filter { role in it.roles }
}
