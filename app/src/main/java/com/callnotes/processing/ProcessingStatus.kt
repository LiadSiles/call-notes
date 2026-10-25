package com.callnotes.processing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/** What a worker is doing, for the UI ([ProcessingStatus.State.phase]). */
object Phase {
    // Transcription worker
    const val LOADING = "loading"
    /** Making the quantized copy of Whisper for a faster preset (once). */
    const val PREPARING_MODEL = "preparing_model"
    const val TRANSCRIBING = "transcribing"
    const val SPEAKERS = "speakers"
    // Summary worker
    const val LOADING_ANALYZER = "loading_analyzer"
    const val WAITING_MEMORY = "waiting_memory"
    const val ANALYZING = "analyzing"
    // Voice notes on their way to becoming tasks
    const val MEMO_TRANSCRIBING = "memo_transcribing"
    const val MEMO_TASK = "memo_task"
    // Both
    const val WAITING_THERMAL = "waiting_thermal"
}

/** The two queues: Whisper (transcription) and Gemma (summaries), one worker each. */
enum class Stage { TRANSCRIBE, SUMMARY }

/**
 * Something the user asked for, waiting its turn in [stage]'s queue: a call ([callId]) or a
 * voice note for a new task ([memoId]; 0 = none). [at] orders the queue — first asked, first
 * served; a voice note keeps the time it was recorded for both of its stages.
 */
data class Request(
    val stage: Stage,
    val callId: Long = 0,
    val memoId: Long = 0,
    val at: Long = System.currentTimeMillis(),
) {
    val isMemo: Boolean get() = memoId != 0L
    fun sameItem(o: Request) = stage == o.stage && callId == o.callId && memoId == o.memoId
}

/** Live progress of the two background workers (transcription, summaries), for the UI. */
object ProcessingStatus {

    data class State(
        val running: Boolean = false,
        /** Call being processed now, or null while loading the model / between calls. */
        val callId: Long? = null,
        /** Progress of the current phase out of [progressMax]; 0/0 when unknown. */
        val progress: Int = 0,
        val progressMax: Int = 0,
        /** A [Phase] constant, or "" between calls. */
        val phase: String = "",
        /** When the current phase started (for "loading the model… 0:24"). */
        val since: Long = System.currentTimeMillis(),
        /** Voice note being turned into a task now, or null. */
        val memoId: Long? = null,
    )

    private val _state = MutableStateFlow(State())
    /** The transcription worker. */
    val state: StateFlow<State> = _state.asStateFlow()

    internal fun set(state: State) { _state.value = state }

    private val _summary = MutableStateFlow(State())
    /** The summary worker; runs at the same time as transcription. */
    val summary: StateFlow<State> = _summary.asStateFlow()

    internal fun setSummary(state: State) { _summary.value = state }

    /**
     * What the user asked for and hasn't started yet, in order. Each worker takes the first
     * request of its stage when it finishes what it is doing — nothing running is ever
     * interrupted for a request — and requests go before automatic work.
     */
    private val _queue = MutableStateFlow<List<Request>>(emptyList())
    val queue: StateFlow<List<Request>> = _queue.asStateFlow()

    private val added = AtomicLong()
    /** Requests added so far (a worker that ends restarts only if a new one came meanwhile). */
    internal val enqueued: Long get() = added.get()

    /** Adds [r] in its place by time; a request already waiting keeps its place. */
    internal fun enqueue(r: Request) {
        var isNew = false
        _queue.update { q ->
            isNew = q.none { it.sameItem(r) }
            if (isNew) (q + r).sortedBy { it.at } else q
        }
        if (isNew) added.incrementAndGet()
    }
    internal fun dequeue(r: Request) = _queue.update { q -> q.filterNot { it.sameItem(r) } }
    internal fun first(stage: Stage): Request? = _queue.value.firstOrNull { it.stage == stage }
    internal fun removeCall(id: Long) = _queue.update { q -> q.filterNot { !it.isMemo && it.callId == id } }
    internal fun removeMemo(id: Long) = _queue.update { q -> q.filterNot { it.memoId == id } }
    internal fun clearQueue() { _queue.value = emptyList() }

    /** The user asked to transcribe call [id]. */
    fun requestTranscription(id: Long) = enqueue(Request(Stage.TRANSCRIBE, callId = id))
    /** The user asked to summarise call [id]. */
    internal fun requestSummary(id: Long) = enqueue(Request(Stage.SUMMARY, callId = id))

    /** 1-based place of call [id] among the requests of [stage]; null if it isn't waiting. */
    fun placeOf(queue: List<Request>, stage: Stage, id: Long): Int? = place(queue, stage) { !it.isMemo && it.callId == id }

    /** 1-based place of voice note [id] among the requests of [stage]; null if it isn't waiting. */
    fun placeOfMemo(queue: List<Request>, stage: Stage, id: Long): Int? = place(queue, stage) { it.memoId == id }

    private inline fun place(queue: List<Request>, stage: Stage, match: (Request) -> Boolean): Int? {
        val i = queue.filter { it.stage == stage }.indexOfFirst(match)
        return if (i < 0) null else i + 1
    }

    /** Bumped whenever the persisted "last run" note changes, so the UI re-reads it. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()
    internal fun touch() { _version.value++ }
}
