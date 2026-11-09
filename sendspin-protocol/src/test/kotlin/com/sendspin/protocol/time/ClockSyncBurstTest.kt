package com.sendspin.protocol.time

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The clock synchronisation burst.
 *
 * The specification requires a burst of at least eight exchanges, and the
 * client feeds the filter only the measurement with the lowest error.
 */
class ClockSyncBurstTest {
    @Test
    fun `holds the measurement until the burst is complete`() {
        val burst = ClockSyncBurst(exchangesPerBurst = 8)
        for (index in 0 until 7) {
            assertNull(burst.record(index.toLong(), 1000), "exchange $index")
        }
        assertEquals(7, burst.count)
    }

    @Test
    fun `returns the measurement with the lowest error`() {
        val burst = ClockSyncBurst(exchangesPerBurst = 8)
        val errors = listOf(1000L, 500L, 900L, 300L, 800L, 700L, 600L, 200L)
        var result: Long? = null
        for ((index, error) in errors.withIndex()) {
            result = burst.record(index.toLong() * 10, error)
        }
        // The eighth exchange has the lowest error, so its measurement wins.
        assertEquals(70L, result)
        assertEquals(0, burst.count)
    }

    @Test
    fun `keeps the lowest error from an earlier exchange`() {
        val burst = ClockSyncBurst(exchangesPerBurst = 4)
        var result: Long? = null
        result = burst.record(11L, 100)
        result = burst.record(22L, 900)
        result = burst.record(33L, 800)
        result = burst.record(44L, 700)
        assertEquals(11L, result)
    }

    @Test
    fun `starts the next burst after the interval`() {
        val burst = ClockSyncBurst(
            exchangesPerBurst = 2,
            burstIntervalMicros = 9_000_000,
        )
        assertTrue(burst.canStartBurst(0))
        burst.startBurst(1_000_000)
        assertFalse(burst.canStartBurst(1_000_000))
        assertFalse(burst.canStartBurst(9_999_999))
        assertTrue(burst.canStartBurst(10_000_000))
    }

    @Test
    fun `starts the next burst with a clean slate`() {
        val burst = ClockSyncBurst(exchangesPerBurst = 4)
        burst.record(1L, 100)
        burst.record(2L, 100)
        assertEquals(2, burst.count)

        burst.startBurst(0)
        assertEquals(0, burst.count)
        // The first exchange of the new burst is not mistaken for the end of
        // the previous one.
        assertNull(burst.record(3L, 100))
    }

    @Test
    fun `uses the defaults the specification requires`() {
        assertEquals(8, ClockSyncBurst.DEFAULT_EXCHANGES_PER_BURST)
        // Consecutive bursts start no more than ten seconds apart.
        assertTrue(ClockSyncBurst.DEFAULT_BURST_INTERVAL_MICROS <= 10_000_000)
    }
}
