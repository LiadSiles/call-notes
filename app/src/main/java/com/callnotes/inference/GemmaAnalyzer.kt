package com.callnotes.inference

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Summary + tasks with Gemma 4 on the device (LiteRT-LM, CPU, text only). Reads the
 * speaker-labelled transcript; answers in JSON (parsed by [AnalysisPrompt]). Greedy
 * decoding and thinking off: this is extraction, not creative writing.
 *
 * Streams the answer (progress = characters written) so a cancel stops generation at
 * once through `Conversation.cancelProcess()` instead of waiting for the full answer.
 */
class GemmaAnalyzer(
    private val context: Context,
    private val spec: ModelSpec,
    private val modelFile: File,
) : Analyzer {

    override val modelId: String get() = spec.id

    private var engine: Engine? = null
    @Volatile private var active: Conversation? = null

    override suspend fun load() = withContext(Dispatchers.IO) {
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

    override suspend fun ask(prompt: String, onChars: (Int) -> Unit): String = withContext(Dispatchers.IO) {
        load()
        val config = ConversationConfig(
            samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
            maxOutputToken = 1536,
            thinkingConfig = ThinkingConfig(enableThinking = false),
        )
        val conversation = engine!!.createConversation(config)
        val finished = CompletableDeferred<Unit>()
        try {
            active = conversation
            suspendCancellableCoroutine { cont ->
                val text = StringBuilder()
                conversation.sendMessageAsync(Contents.of(Content.Text(prompt)), object : MessageCallback {
                    override fun onMessage(message: Message) {
                        text.append(message.toString())
                        onChars(text.length)
                    }
                    override fun onDone() {
                        finished.complete(Unit)
                        if (cont.isActive) cont.resume(text.toString().trim())
                    }
                    override fun onError(throwable: Throwable) {
                        finished.complete(Unit)
                        if (cont.isActive) cont.resumeWithException(throwable)
                    }
                })
                cont.invokeOnCancellation { runCatching { conversation.cancelProcess() } }
            }
        } finally {
            active = null
            // Never close a conversation the native side is still writing into.
            withContext(NonCancellable) { withTimeoutOrNull(15_000) { finished.await() } }
            if (finished.isCompleted) runCatching { conversation.close() }
        }
    }

    /** Stops the current answer now: generation ends at the next token. */
    override fun abort() {
        active?.let { runCatching { it.cancelProcess() } }
    }

    override fun close() {
        engine?.close()
        engine = null
    }
}
