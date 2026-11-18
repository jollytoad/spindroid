package app.gubbins.sendspin.time

/**
 * The burst strategy that the client uses for clock synchronisation.
 *
 * The specification requires bursts. The client sends one `client/time`
 * message, waits for the reply, and sends the next. A burst holds at least
 * eight exchanges. The client feeds the filter only the measurement from the
 * burst with the smallest error, because that is the one most likely to be
 * free of network delay.
 *
 * The class also paces the bursts. Consecutive bursts must start no more
 * than ten seconds apart, so the caller asks whether a burst may start and
 * then starts one.
 */
public class ClockSyncBurst(
    /** The number of exchanges in a burst. */
    public val exchangesPerBurst: Int = DEFAULT_EXCHANGES_PER_BURST,
    /** The gap between bursts, in microseconds. */
    private val burstIntervalMicros: Long = DEFAULT_BURST_INTERVAL_MICROS,
) {
    /**
     * One measurement of the clock, and how wrong it may be.
     *
     * @property measurement the observed offset between the two clocks
     * @property maxError the worst-case error of the measurement
     * @property at the local time the measurement was taken, in microseconds
     */
    public data class Sample(
        val measurement: Long,
        val maxError: Long,
        val at: Long,
    )

    private var measurements = 0
    private var best: Sample? = null
    private var nextBurstAllowedAt: Long = 0

    /** The number of exchanges of the burst that has not finished. */
    public val count: Int get() = measurements

    /**
     * Starts a burst.
     *
     * The next burst may start after the interval has passed.
     */
    public fun startBurst(nowMicros: Long) {
        measurements = 0
        best = null
        nextBurstAllowedAt = nowMicros + burstIntervalMicros
    }

    /**
     * Reports whether a burst may start.
     *
     * @param nowMicros the current local time
     */
    public fun canStartBurst(nowMicros: Long): Boolean = nowMicros >= nextBurstAllowedAt

    /**
     * Records one exchange and reports whether the burst has finished.
     *
     * The caller feeds the filter with the sample this returns.
     *
     * @param measurement the observed offset
     * @param maxError the worst-case error of the measurement
     * @param at the local time the measurement was taken
     * @return the sample to feed the filter, or null while the burst is
     *   still running
     */
    public fun record(measurement: Long, maxError: Long, at: Long): Sample? {
        val current = best
        if (current == null || maxError < current.maxError) {
            best = Sample(measurement, maxError, at)
        }
        measurements++
        if (measurements < exchangesPerBurst) return null
        val sample = best ?: return null
        measurements = 0
        best = null
        return sample
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
