package com.callnotes.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Live state of the recording service, for the UI. */
object ServiceStatus {

    data class State(
        val connected: Boolean = false,
        /** Call detection is running (needs the phone-state permission). */
        val monitoring: Boolean = false,
        val recordingNow: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    internal fun update(transform: (State) -> State) {
        _state.value = transform(_state.value)
    }
}
