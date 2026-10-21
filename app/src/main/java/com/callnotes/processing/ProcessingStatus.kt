package com.callnotes.processing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    internal fun set(state: State) { _state.value = state }

    /** Bumped whenever the persisted "last run" note changes, so the UI re-reads it. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()
    internal fun touch() { _version.value++ }
}
