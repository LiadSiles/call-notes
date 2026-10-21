package com.callnotes.inference

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Summary + tasks with Gemma 4 on the device (LiteRT-LM, CPU, text only). Reads the
 * speaker-labelled transcript; answers in JSON (parsed by [AnalysisPrompt]). Greedy
 * decoding and thinking off: this is extraction, not creative writing.
 */
class GemmaAnalyzer(
    private val context: Context,
    private val spec: ModelSpec,
    private val modelFile: File,
) : AutoCloseable {

    val modelId: String get() = spec.id

    private var engine: Engine? = null

    suspend fun load() = withContext(Dispatchers.IO) {
        if (engine != null) return@withContext
        engine = Engine(
            EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = Backend.CPU(),
                maxNumTokens = 8192,
                cacheDir = context.cacheDir.absolutePath,
            )
        ).also { it.initialize() }
    }

    /** Returns the raw answer; parsing is separate so failures can be logged with the text. */
    suspend fun ask(prompt: String): String = withContext(Dispatchers.IO) {
        load()
        val config = ConversationConfig(
            samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
            maxOutputToken = 1536,
            thinkingConfig = ThinkingConfig(enableThinking = false),
        )
        engine!!.createConversation(config).use { conversation ->
            conversation.sendMessage(Contents.of(Content.Text(prompt))).toString().trim()
        }
    }

    override fun close() {
        engine?.close()
        engine = null
    }
}
