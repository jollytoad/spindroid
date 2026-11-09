package com.sendspin.protocol.time

/**
 * The burst strategy that the client uses for clock synchronisation.
 *
 * The specification requires bursts. The client sends one `client/time`
 * message, waits for the reply, and sends the next. A burst holds at least
 * eight exchanges. The client feeds the filter only the measurement from the
 * burst with the smallest error, because that is the one most likely to be
 * free of network delay.
 *
 * Between bursts the client waits, so the two clocks are sampled over a
 * longer interval without the exchanges interfering with each other.
 */
public class ClockSyncBurst(
    /** The number of exchanges in a burst. */
    private val exchangesPerBurst: Int = DEFAULT_EXCHANGES_PER_BURST,
    /** The gap between bursts, in microseconds. */
    private val burstIntervalMicros: Long = DEFAULT_BURST_INTERVAL_MICROS,
) {
    private var measurements = 0
    private var bestMeasurement: Long = 0
    private var bestMaxError: Long = 0
    private var nextBurstAllowedAt: Long = 0

    /** The number of exchanges in the current burst. */
    public val count: Int get() = measurements

    /**
     * Records one exchange and reports whether the burst is complete.
     *
     * The caller feeds the filter with the measurement that this returns.
     *
     * @param measurement the observed offset
     * @param maxError the worst-case error of the measurement
     * @return the measurement to feed the filter, or null while the burst is
     *   still running
     */
    public fun record(measurement: Long, maxError: Long): Long? {
        if (measurements == 0 || maxError < bestMaxError) {
            bestMeasurement = measurement
            bestMaxError = maxError
        }
        measurements++
        if (measurements < exchangesPerBurst) return null
        val best = bestMeasurement
        measurements = 0
        bestMaxError = 0
        nextBurstAllowedAt = Long.MAX_VALUE
        return best
    }

    /**
     * Reports whether the client may start a new burst.
     *
     * @param nowMicros the current local time
     */
    public fun canStartBurst(nowMicros: Long): Boolean = nowMicros >= nextBurstAllowedAt

    /**
     * Records that a burst has started, so the next one waits.
     *
     * @param nowMicros the current local time
     */
    public fun startBurst(nowMicros: Long) {
        measurements = 0
        bestMaxError = 0
        nextBurstAllowedAt = nowMicros + burstIntervalMicros
    }

    public companion object {
        /** The number of exchanges in a burst. */
        public const val DEFAULT_EXCHANGES_PER_BURST: Int = 8

        /**
         * The gap between bursts, in microseconds.
         *
         * The specification requires consecutive bursts to start no more than
         * ten seconds apart. Nine seconds leaves a margin.
         */
        public const val DEFAULT_BURST_INTERVAL_MICROS: Long = 9_000_000
    }
}
