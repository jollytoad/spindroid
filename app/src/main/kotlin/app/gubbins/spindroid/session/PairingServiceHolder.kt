package app.gubbins.spindroid.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The state of the listener that waits for a server.
 *
 * The pairing starts before the screen capture, so the service runs in a
 * mode of its own. The screen and the pairing manager need to know when
 * the listener is really up, because the server cannot find the phone
 * before the advertisement runs.
 */
object PairingServiceHolder {
    private val mutableRunning = MutableStateFlow(false)
    private val mutableError = MutableStateFlow<String?>(null)

    /** Whether the listener and the advertisement run. */
    val running: StateFlow<Boolean> = mutableRunning.asStateFlow()

    /** The reason the listener failed, or null when it has not failed. */
    val error: StateFlow<String?> = mutableError.asStateFlow()

    /** Says whether the listener runs. */
    fun publishRunning(isRunning: Boolean) {
        mutableRunning.value = isRunning
    }

    /** Publishes the reason the listener failed. */
    fun publishError(message: String?) {
        mutableError.value = message
    }

    /** Forgets the state, because the service stopped. */
    fun reset() {
        mutableRunning.value = false
        mutableError.value = null
    }
}
