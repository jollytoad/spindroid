package com.sendspin.sender.capture

/**
 * What the capture does, for the status panel.
 *
 * The panel shows this, so the user can tell a silent capture from a
 * capture that never started. A capture that never starts reports an
 * error. A capture that runs without sound reports blocks, but a level of
 * zero.
 */
data class CaptureStatus(
    /** The capture runs. */
    val running: Boolean = false,
    /** The blocks that arrived in the last second. */
    val blocksPerSecond: Int = 0,
    /** The loudest sample of the last window, from 0 to 32767. */
    val peak: Int = 0,
    /** The last error of the capture. */
    val error: String? = null,
)
