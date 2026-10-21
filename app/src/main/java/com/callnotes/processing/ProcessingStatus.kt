package com.callnotes.processing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Live progress of background transcription, for the UI. */
object ProcessingStatus {

    data class State(
        val running: Boolean = false,
        /** Call being processed now, or null while loading the model / between calls. */
        val callId: Long? = null,
        val chunksDone: Int = 0,
        val chunksTotal: Int = 0,
        /** "loading", "transcribing", "waiting_thermal". */
        val phase: String = "",
        /** When the current phase started (for "loading the model… 0:24"). */
        val since: Long = System.currentTimeMillis(),
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    internal fun set(state: State) { _state.value = state }

    /**
     * Calls the user asked to transcribe by hand that haven't started yet. They jump the
     * queue; the UI shows them as "starting" right away instead of looking unchanged.
     */
    private val _requested = MutableStateFlow<List<Long>>(emptyList())
    val requested: StateFlow<List<Long>> = _requested.asStateFlow()

    internal fun request(id: Long) = _requested.update { (it - id) + id }
    internal fun unrequest(id: Long) = _requested.update { it - id }
    internal fun clearRequests() { _requested.value = emptyList() }
    internal fun firstRequested(): Long? = _requested.value.firstOrNull()

    /** Bumped whenever the persisted "last run" note changes, so the UI re-reads it. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()
    internal fun touch() { _version.value++ }
}
