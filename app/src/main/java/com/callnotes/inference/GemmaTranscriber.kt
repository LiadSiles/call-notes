package com.callnotes.inference

import android.content.Context
import com.callnotes.audio.Chunker
import com.callnotes.audio.Wav
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Transcribes with Gemma 4 (E2B/E4B) through LiteRT-LM, using the model's native audio
 * input. One fresh conversation per chunk so context never accumulates; greedy decoding
 * and thinking off for faithful, fast output. CPU backend: the GPU path is reported to
 * crash on phones under 12 GB RAM.
 */
class GemmaTranscriber(
    private val context: Context,
    private val spec: ModelSpec,
    private val modelFile: File,
) : Transcriber {

    override val modelId: String get() = spec.id

    private var engine: Engine? = null

    private fun engine(): Engine = engine ?: Engine(
        EngineConfig(
            modelPath = modelFile.absolutePath,
            backend = Backend.CPU(),
            audioBackend = Backend.CPU(),
            maxNumTokens = 4096,
            cacheDir = context.cacheDir.absolutePath,
        )
    ).also {
        it.initialize() // slow (seconds); always called from Dispatchers.IO
        engine = it
    }

    override suspend fun transcribe(
        audio: File,
        languageHint: String,
        onProgress: (Int, Int) -> Unit,
    ): Transcript = withContext(Dispatchers.IO) {
        val info = Wav.readInfo(audio) ?: error("Not a WAV file: ${audio.name}")
        require(info.isWorkingFormat) { "Expected 16 kHz mono PCM16, got $info" }

        val chunks = Chunker.plan(audio, info)
        val tmp = File(context.cacheDir, "chunk.wav")
        val segments = ArrayList<Transcript.Segment>()
        onProgress(0, chunks.size)

        chunks.forEachIndexed { i, chunk ->
            coroutineContext.ensureActive()
            if (!chunk.silent) {
                val samples = Wav.readSamples(audio, info, chunk.startSample,
                    (chunk.endSample - chunk.startSample).toInt())
                Wav.write(tmp, samples)
                val text = runChunk(tmp, languageHint)
                segments += Transcript.Segment(chunk.startMs, chunk.endMs, text)
            }
            onProgress(i + 1, chunks.size)
        }
        tmp.delete()

        Transcript(TranscriptMerger.stripAnnouncements(TranscriptMerger.merge(segments)))
    }

    private fun runChunk(wav: File, languageHint: String): String {
        val config = ConversationConfig(
            systemInstruction = Contents.of(SYSTEM),
            samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
            maxOutputToken = 512,
            thinkingConfig = ThinkingConfig(enableThinking = false),
        )
        engine().createConversation(config).use { conversation ->
            val reply = conversation.sendMessage(
                Contents.of(Content.AudioFile(wav.absolutePath), Content.Text(prompt(languageHint)))
            )
            return reply.toString().trim()
        }
    }

    override fun close() {
        engine?.close()
        engine = null
    }

    private companion object {
        const val SYSTEM =
            "You are a speech-to-text engine. You output only the exact words spoken in the audio."

        fun prompt(languageHint: String): String {
            val lang = if (languageHint == "he") "Hebrew (with occasional English words)" else languageHint
            return "Transcribe this phone-call audio verbatim, in the language it is spoken " +
                "(usually $lang). Write Hebrew in Hebrew letters. Do not translate, summarize, " +
                "explain or add anything. If there is no speech, output nothing."
        }
    }
}
