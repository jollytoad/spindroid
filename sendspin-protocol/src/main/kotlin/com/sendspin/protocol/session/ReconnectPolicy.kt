package com.sendspin.protocol.session

/**
 * The wait between two attempts to connect.
 *
 * The wait grows with every failed attempt, so a server that is down does
 * not keep the phone busy. It never grows past the ceiling, because the
 * user expects the app to come back on its own.
 *
 * The count is reset once a connection works, so a drop after a long
 * session starts the wait over.
 */
public class ReconnectPolicy(
    /** The wait after the first failure, in milliseconds. */
    private val baseDelayMillis: Long = DEFAULT_BASE_DELAY_MILLIS,
    /** The longest wait, in milliseconds. */
    private val maxDelayMillis: Long = DEFAULT_MAX_DELAY_MILLIS,
) {
    /** The number of failures since the last working connection. */
    public var attempt: Int = 0
        private set

    /**
     * The wait before the next attempt.
     *
     * @return the delay in milliseconds
     */
    public fun nextDelayMillis(): Long {
        attempt += 1
        return minOf(baseDelayMillis * attempt, maxDelayMillis)
    }

    /** Forgets the failures, because a connection worked. */
    public fun reset() {
        attempt = 0
    }

    public companion object {
        /** The wait after the first failure, in milliseconds. */
        public const val DEFAULT_BASE_DELAY_MILLIS: Long = 1_000

        /** The longest wait, in milliseconds. */
        public const val DEFAULT_MAX_DELAY_MILLIS: Long = 30_000
    }
}
