package com.sendspin.protocol.time

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * The clock filter that maps between this device's clock and a server's.
 *
 * It is a port of the C++ reference implementation in the `Sendspin/time-filter`
 * repository. The filter tracks the offset and the drift between the two
 * clocks with a two-dimensional Kalman filter.
 *
 * All times are microseconds. The filter keeps its state in double-precision
 * floating point.
 */
public class TimeFilter(
    /** The tuning parameters. The defaults are the ones the specification gives. */
    public val config: Config = Config(),
) {
    /**
     * The tuning parameters of the filter.
     *
     * @param processStdDev the offset random walk, in microseconds per
     *   square root microsecond
     * @param driftProcessStdDev the drift random walk, in 1 per square root
     *   microsecond
     * @param forgetFactor the factor that scales the covariances when a
     *   residual is large
     * @param adaptiveCutoff the multiple of the measurement error above which
     *   a residual counts as large
     * @param minSamples the number of samples before the adaptive forgetting
     *   applies
     * @param driftSignificanceThreshold the signal-to-noise ratio the drift
     *   must reach before the conversions apply it
     * @param maxErrorScale the scale from the measurement error to the
     *   measurement standard deviation
     */
    public data class Config(
        val processStdDev: Double = 0.0,
        val driftProcessStdDev: Double = 1e-11,
        val forgetFactor: Double = 2.0,
        val adaptiveCutoff: Double = 3.0,
        val minSamples: Int = 100,
        val driftSignificanceThreshold: Double = 2.0,
        val maxErrorScale: Double = 0.5,
    )

    private val processVariance = config.processStdDev * config.processStdDev
    private val driftProcessVariance = config.driftProcessStdDev * config.driftProcessStdDev
    private val forgetVarianceFactor = config.forgetFactor * config.forgetFactor
    private val driftSignificanceThresholdSquared =
        config.driftSignificanceThreshold * config.driftSignificanceThreshold

    private var lastUpdate: Long = 0
    private var offset: Double = 0.0
    private var drift: Double = 0.0
    private var offsetCovariance: Double = Double.POSITIVE_INFINITY
    private var offsetDriftCovariance: Double = 0.0
    private var driftCovariance: Double = 0.0
    private var useDrift: Boolean = false

    /** The number of measurements the filter has accepted. */
    public var count: Int = 0
        private set

    /**
     * The current estimate of the offset, in microseconds.
     *
     * The offset is the server's clock minus this device's clock.
     */
    public val offsetEstimate: Double get() = offset

    /**
     * The current estimate of the drift, in microseconds per microsecond.
     *
     * The drift is the rate at which the offset changes.
     */
    public val driftEstimate: Double get() = drift

    /** Whether the drift estimate is significant enough to be applied. */
    public val isDriftApplied: Boolean get() = useDrift

    /**
     * Adds a measurement.
     *
     * @param measurement the observed offset, which is
     *   `((T2 - T1) + (T3 - T4)) / 2`
     * @param maxError the worst-case error of the measurement, which is
     *   `((T4 - T1) - (T3 - T2)) / 2`
     * @param timeAdded the local time the measurement was taken
     */
    public fun update(measurement: Long, maxError: Long, timeAdded: Long) {
        // A measurement that is not newer than the last one is ignored. The
        // same guard stops the time step from being zero.
        if (timeAdded <= lastUpdate) return
        // A measurement with no error would collapse the covariance, and a
        // negative error is not a half round-trip delay.
        if (maxError <= 0) return

        val dt = (timeAdded - lastUpdate).toDouble()
        val dtSquared = dt * dt
        lastUpdate = timeAdded

        val updateStdDev = maxError * config.maxErrorScale
        val measurementVariance = updateStdDev * updateStdDev
        val measured = measurement.toDouble()

        // The first measurement sets the offset.
        if (count <= 0) {
            count++
            offset = measured
            offsetCovariance = measurementVariance
            drift = 0.0
            return
        }

        // The second gives a first drift estimate from the difference.
        if (count == 1) {
            count++
            drift = (measured - offset) / dt
            offset = measured
            driftCovariance = (offsetCovariance + measurementVariance) / dtSquared
            offsetCovariance = measurementVariance
            return
        }

        // Predict.
        val predictedOffset = offset + drift * dt
        var newDriftCovariance = driftCovariance + dt * driftProcessVariance
        var newOffsetDriftCovariance = offsetDriftCovariance + driftCovariance * dt
        var newOffsetCovariance = offsetCovariance +
            2 * offsetDriftCovariance * dt +
            driftCovariance * dtSquared +
            dt * processVariance

        // The residual measures how far the measurement fell from the
        // prediction.
        val residual = measured - predictedOffset
        if (count < config.minSamples) {
            // Building a history first keeps the filter stable.
            count++
        } else if (abs(residual) > config.adaptiveCutoff * maxError) {
            // A large residual means the network or the server's clock
            // changed. Scaling the covariances lets the filter recover
            // faster.
            newDriftCovariance *= forgetVarianceFactor
            newOffsetDriftCovariance *= forgetVarianceFactor
            newOffsetCovariance *= forgetVarianceFactor
        }

        // Correct.
        val uncertainty = 1.0 / (newOffsetCovariance + measurementVariance)
        val offsetGain = newOffsetCovariance * uncertainty
        val driftGain = newOffsetDriftCovariance * uncertainty

        offset = predictedOffset + offsetGain * residual
        drift += driftGain * residual

        driftCovariance = newDriftCovariance - driftGain * newOffsetDriftCovariance
        offsetDriftCovariance = newOffsetDriftCovariance - driftGain * newOffsetCovariance
        offsetCovariance = newOffsetCovariance - offsetGain * newOffsetCovariance

        // The drift only applies once it is clearly above the noise.
        useDrift = drift * drift > driftSignificanceThresholdSquared * driftCovariance
    }

    /**
     * Converts a local time to the server's clock.
     *
     * The drift accumulates over the time since the last measurement, so the
     * conversion stays accurate between measurements.
     */
    public fun computeServerTime(clientTime: Long): Long {
        val effectiveDrift = if (useDrift) drift else 0.0
        val offset = round(offset + effectiveDrift * (clientTime - lastUpdate))
        return clientTime + offset
    }

    /**
     * Converts a server time to the local clock.
     *
     * This is the inverse of [computeServerTime].
     */
    public fun computeClientTime(serverTime: Long): Long {
        val effectiveDrift = if (useDrift) drift else 0.0
        return round(
            (serverTime - offset + effectiveDrift * lastUpdate) / (1.0 + effectiveDrift),
        )
    }

    /**
     * The standard deviation of the offset estimate, in microseconds.
     *
     * A small value means the two clocks are in step.
     */
    public fun error(): Double = sqrt(offsetCovariance)

    /** The variance of the offset estimate, in microseconds squared. */
    public fun covariance(): Double = offsetCovariance

    /** Clears the filter, so it starts again from the next measurement. */
    public fun reset() {
        count = 0
        offset = 0.0
        drift = 0.0
        offsetCovariance = Double.POSITIVE_INFINITY
        offsetDriftCovariance = 0.0
        driftCovariance = 0.0
        lastUpdate = 0
        useDrift = false
    }

    /**
     * Rounds to the nearest whole number, with halves going away from zero.
     *
     * The C++ reference rounds this way, and the result must match it to
     * the microsecond.
     */
    private fun round(value: Double): Long =
        if (value >= 0) floor(value + 0.5).toLong() else ceil(value - 0.5).toLong()
}
