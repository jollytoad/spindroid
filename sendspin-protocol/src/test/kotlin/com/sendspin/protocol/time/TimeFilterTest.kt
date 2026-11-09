package com.sendspin.protocol.time

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The clock filter, against the reference algorithm.
 *
 * The expected values come from the C++ reference implementation in the
 * `Sendspin/time-filter` repository, run over the same measurements.
 */
class TimeFilterTest {
    /**
     * One measurement and the state of the filter after it.
     *
     * @param index the position of the measurement in the sequence
     * @param measurement the observed offset
     * @param maxError the worst-case error of the measurement
     * @param timeAdded the local time the measurement was taken
     * @param offset the offset estimate after the measurement
     * @param drift the drift estimate after the measurement
     * @param error the standard deviation of the offset estimate
     * @param serverTime the server time for the local time
     * @param clientTime the local time for the server time
     * @param useDrift whether the drift was applied
     * @param count the number of accepted measurements
     */
    private class Sample(
        val index: Int,
        val measurement: Long,
        val maxError: Long,
        val timeAdded: Long,
        val offset: Double,
        val drift: Double,
        val error: Double,
        val serverTime: Long,
        val clientTime: Long,
        val useDrift: Boolean,
        val count: Int,
    )

    private val samples = listOf(
        Sample(0, 999584, 1000, 50000, 999584.0, 0.0, 500.0, 1049584, 50000, false, 1),
        Sample(1, 1000356, 1000, 100000, 1000356.0, 0.01544, 500.0, 1100356, 100000, false, 2),
        Sample(
            2, 1000421, 1000, 150000, 1000597.75, 0.008369999999999999, 433.0127018922193,
            1150598, 150000, false, 3,
        ),
        Sample(
            9, 999970, 1000, 500000, 1000020.6932966023, -0.0009226446280998819,
            306.4204305479062, 1500021, 500000, false, 10,
        ),
        Sample(
            49, 1000720, 1000, 2500000, 1000271.6067548465, 7.63389624829491e-05,
            140.6953045786404, 3500272, 2500000, false, 50,
        ),
        Sample(
            99, 1000700, 1000, 5000000, 1000449.248699536, 7.922746399332708e-05,
            99.74692549321965, 6000449, 5000000, true, 100,
        ),
        Sample(
            149, 1000862, 1000, 7500000, 1000694.2071882556, 8.971498671673821e-05,
            81.51249396830409, 8500694, 7500000, true, 100,
        ),
    )

    /** The measurements that lead to the samples above. */
    private fun measurements(): List<Triple<Long, Long, Long>> {
        val result = mutableListOf<Triple<Long, Long, Long>>()
        var time = 0L
        var state = 12345L
        repeat(150) {
            time += 50_000
            state = (1103515245 * state + 12345) % (1L shl 31)
            val noise = (state % 1001) - 500
            val measurement = Math.round(1_000_000.0 + 1e-4 * time) + noise
            result.add(Triple(measurement, 1000L, time))
        }
        return result
    }

    @Test
    fun `matches the reference implementation`() {
        val filter = TimeFilter()
        val measurements = measurements()
        val expected = samples.associateBy { it.index }

        for ((index, measurement) in measurements.withIndex()) {
            val (value, maxError, timeAdded) = measurement
            filter.update(value, maxError, timeAdded)

            val sample = expected[index] ?: continue
            assertEquals(sample.count, filter.count, "count at sample $index")
            assertEquals(sample.offset, filter.offsetEstimate, 1e-9, "offset at $index")
            assertEquals(sample.drift, filter.driftEstimate, 1e-12, "drift at $index")
            assertEquals(sample.error, sqrt(filter.covariance()), 1e-9, "error at $index")
            assertEquals(sample.useDrift, filter.isDriftApplied, "drift use at $index")
            assertEquals(
                sample.serverTime,
                filter.computeServerTime(sample.timeAdded),
                "server time at $index",
            )
            assertEquals(
                sample.clientTime,
                filter.computeClientTime(sample.serverTime),
                "client time at $index",
            )
        }
    }

    @Test
    fun `converges on the true offset`() {
        val filter = TimeFilter()
        var time = 0L
        var state = 12345L
        repeat(150) {
            time += 50_000
            state = (1103515245 * state + 12345) % (1L shl 31)
            val noise = (state % 1001) - 500
            val measurement = Math.round(1_000_000.0 + 1e-4 * time) + noise
            filter.update(measurement, 1000, time)
        }

        // The true offset at this point is one second plus the drift.
        val trueOffset = 1_000_000.0 + 1e-4 * time
        assertTrue(
            abs(filter.offsetEstimate - trueOffset) < 1000.0,
            "The offset estimate ${filter.offsetEstimate} is within a millisecond of $trueOffset",
        )
        // The drift is 100 parts per million. The estimate is close, but the
        // noise in the measurements keeps it from landing on the exact
        // value.
        assertTrue(
            abs(filter.driftEstimate - 1e-4) < 2e-5,
            "The drift estimate ${filter.driftEstimate} is close to 0.0001",
        )
        assertTrue(filter.isDriftApplied)
    }

    @Test
    fun `converts between the two clocks`() {
        val filter = TimeFilter()
        var time = 0L
        var state = 12345L
        repeat(150) {
            time += 50_000
            state = (1103515245 * state + 12345) % (1L shl 31)
            val noise = (state % 1001) - 500
            val measurement = Math.round(1_000_000.0 + 1e-4 * time) + noise
            filter.update(measurement, 1000, time)
        }

        // The two conversions are inverses of each other.
        for (clientTime in listOf(0L, 1_000_000L, 7_500_000L, 9_999_999L)) {
            val serverTime = filter.computeServerTime(clientTime)
            val back = filter.computeClientTime(serverTime)
            assertTrue(
                abs(back - clientTime) <= 1,
                "The round trip from $clientTime gave $back",
            )
        }
    }

    @Test
    fun `discards a measurement that is not newer`() {
        val filter = TimeFilter()
        filter.update(1000, 100, 5000)
        val offset = filter.offsetEstimate

        // The same time, and an earlier time, both change nothing.
        filter.update(9999, 100, 5000)
        filter.update(9999, 100, 4000)
        assertEquals(offset, filter.offsetEstimate)
        assertEquals(1, filter.count)
    }

    @Test
    fun `discards a measurement without an error`() {
        val filter = TimeFilter()
        filter.update(1000, 0, 5000)
        assertEquals(0, filter.count)
        assertEquals(0.0, filter.offsetEstimate)
    }

    @Test
    fun `starts again after a reset`() {
        val filter = TimeFilter()
        filter.update(1000, 100, 5000)
        filter.reset()
        assertEquals(0, filter.count)
        assertEquals(0.0, filter.offsetEstimate)
        assertEquals(0.0, filter.driftEstimate)
        assertFalse(filter.isDriftApplied)
    }

    @Test
    fun `does not apply the drift before it is significant`() {
        val filter = TimeFilter()
        // Two measurements with the same offset give no drift at all.
        filter.update(1000, 100, 1000)
        filter.update(1000, 100, 2000)
        assertFalse(filter.isDriftApplied)
    }
}
