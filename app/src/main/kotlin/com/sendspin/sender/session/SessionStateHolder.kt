package com.sendspin.sender.session

import com.sendspin.protocol.session.SourceSessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The state the service shares with the screen.
 *
 * The service runs on its own, and the screen comes and goes. The holder
 * keeps the last state, so a screen that opens shows the state of the
 * connection right away.
 *
 * The holder also keeps whether the service runs. The screen cannot ask the
 * service, because a screen that was just built knows nothing about it.
 */
object SessionStateHolder {
    private val mutableState = MutableStateFlow(SourceSessionState())
    private val mutableRunning = MutableStateFlow(false)

    /** The state of the connection. */
    val state: StateFlow<SourceSessionState> = mutableState.asStateFlow()

    /** Whether the service is running. */
    val running: StateFlow<Boolean> = mutableRunning.asStateFlow()

    /** Publishes a new state. */
    fun publish(newState: SourceSessionState) {
        mutableState.value = newState
    }

    /**
     * Forgets the last state.
     *
     * A new session starts with no server, and the screen must not show the
     * names and the pairing of a session that has ended.
     */
    fun reset() {
        mutableState.value = SourceSessionState()
    }

    /** Says whether the service is running. */
    fun publishRunning(isRunning: Boolean) {
        mutableRunning.value = isRunning
    }
}
