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
 * Summary + tasks with Gemma 4 on the device (LiteRT-LM, CPU, text only). Answers in JSON
 * (prompts and checks in [CallSummarizer]). Greedy decoding: this is extraction, not
 * creative writing. [thinking]: the model reasons before each answer (more careful, much
 * slower; the reasoning is not part of the answer) — it gets a larger context and longer
 * answers for it. A [conversation] keeps its history on the native side, so follow-up
 * questions about a transcript don't make the model read it again.
 *
 * Streams each answer (progress = characters written) so a cancel stops generation at
 * once through `Conversation.cancelProcess()` instead of waiting for the full answer.
 */
class GemmaAnalyzer(
    private val context: Context,
    private val spec: ModelSpec,
    private val modelFile: File,
    private val thinking: Boolean = false,
) : Analyzer {

    override val modelId: String get() = spec.id

    private var engine: Engine? = null
    @Volatile private var active: Conversation? = null

    /** Context the engine was opened with (tokens: transcript part + questions + answers). */
    var contextTokens = 0
        private set

    override suspend fun load() = withContext(Dispatchers.IO) {
        if (engine != null) return@withContext
        engine = if (thinking) {
            // Reasoning needs room; fall back to the usual size if the model file refuses it.
            runCatching { open(THINKING_CONTEXT) }.getOrElse { open(CONTEXT) }
        } else {
            open(CONTEXT)
        }
    }

    private fun open(tokens: Int): Engine {
        val e = Engine(
            EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = Backend.CPU(),
                maxNumTokens = tokens,
                cacheDir = context.cacheDir.absolutePath,
            )
        )
        try {
            e.initialize()
        } catch (t: Throwable) {
            runCatching { e.close() }
            throw t
        }
        contextTokens = tokens
        return e
    }

    override suspend fun <T> conversation(block: suspend (Analyzer.Chat) -> T): T = withContext(Dispatchers.IO) {
        load()
        val config = ConversationConfig(
            samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
            maxOutputToken = if (thinking) 4096 else 2048,
            thinkingConfig = ThinkingConfig(enableThinking = thinking),
        )
        val conversation = engine!!.createConversation(config)
        /** The answer being written now, if any. */
        var writing: CompletableDeferred<Unit>? = null
        try {
            active = conversation
            block(object : Analyzer.Chat {
                override suspend fun ask(prompt: String, onChars: (Int) -> Unit): String {
                    val finished = CompletableDeferred<Unit>()
                    writing = finished
                    return send(conversation, prompt, onChars, finished)
                }
            })
        } finally {
            active = null
            // Never close a conversation the native side is still writing into.
            val last = writing
            withContext(NonCancellable) { last?.let { withTimeoutOrNull(15_000) { it.await() } } }
            if (last == null || last.isCompleted) runCatching { conversation.close() }
        }
    }

    /** One question in [conversation]; [finished] completes when the native side is done. */
    private suspend fun send(conversation: Conversation, prompt: String, onChars: (Int) -> Unit, finished: CompletableDeferred<Unit>): String =
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

    /** Stops the current answer now: generation ends at the next token. */
    override fun abort() {
        active?.let { runCatching { it.cancelProcess() } }
    }

    override fun close() {
        engine?.close()
        engine = null
    }

    private companion object {
        const val CONTEXT = 8192
        const val THINKING_CONTEXT = 16384
    }
}
