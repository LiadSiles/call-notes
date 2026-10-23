package com.callnotes.processing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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
     * Calls the user asked to transcribe by hand that haven't started yet. They jump the
     * queue; the UI shows them as "starting" right away instead of looking unchanged.
     */
    private val _requested = MutableStateFlow<List<Long>>(emptyList())
    val requested: StateFlow<List<Long>> = _requested.asStateFlow()

    internal fun request(id: Long) = _requested.update { (it - id) + id }
    internal fun unrequest(id: Long) = _requested.update { it - id }
    internal fun clearRequests() { _requested.value = emptyList(); _analysisRequested.value = emptyList() }
    internal fun firstRequested(): Long? = _requested.value.firstOrNull()

    /** Calls the user asked to summarise by hand; they go first in the analysis pass. */
    private val _analysisRequested = MutableStateFlow<List<Long>>(emptyList())
    val analysisRequested: StateFlow<List<Long>> = _analysisRequested.asStateFlow()
    internal fun requestAnalysis(id: Long) = _analysisRequested.update { (it - id) + id }
    internal fun unrequestAnalysis(id: Long) = _analysisRequested.update { it - id }
    internal fun firstAnalysisRequested(): Long? = _analysisRequested.value.firstOrNull()

    /** Bumped whenever the persisted "last run" note changes, so the UI re-reads it. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()
    internal fun touch() { _version.value++ }
}
