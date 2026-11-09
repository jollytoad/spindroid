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
 */
object SessionStateHolder {
    private val mutableState = MutableStateFlow(SourceSessionState())

    /** The state of the connection. */
    val state: StateFlow<SourceSessionState> = mutableState.asStateFlow()

    /** Publishes a new state. */
    fun publish(newState: SourceSessionState) {
        mutableState.value = newState
    }
}
