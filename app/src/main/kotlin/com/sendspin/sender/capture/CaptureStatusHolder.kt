package com.sendspin.sender.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The capture status that the service shares with the screen.
 *
 * The service and the capture write to it from different threads, so every
 * change is made under a lock. The holder keeps the last status, so a
 * screen that opens shows the state of the capture right away.
 */
object CaptureStatusHolder {
    private val lock = Any()
    private val mutableState = MutableStateFlow(CaptureStatus())

    /** The status of the capture. */
    val state: StateFlow<CaptureStatus> = mutableState.asStateFlow()

    /** Says whether the capture runs. */
    fun publishRunning(running: Boolean) {
        update { it.copy(running = running) }
    }

    /** Reports the measurements of the capture. */
    fun publishMeasurements(blocksPerSecond: Int, peak: Int) {
        update { it.copy(blocksPerSecond = blocksPerSecond, peak = peak) }
    }

    /** Reports the last error of the capture. */
    fun publishError(message: String?) {
        update { it.copy(error = message) }
    }

    /** Clears the status, when the capture stops. */
    fun reset() {
        update { CaptureStatus() }
    }

    /** Makes one change to the status. */
    private fun update(change: (CaptureStatus) -> CaptureStatus) {
        synchronized(lock) {
            mutableState.value = change(mutableState.value)
        }
    }
}
