package com.sendspin.protocol.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The wait between two attempts to connect.
 */
class ReconnectPolicyTest {
    @Test
    fun `the wait grows with every failure`() {
        val policy = ReconnectPolicy(baseDelayMillis = 1000, maxDelayMillis = 30000)
        assertEquals(1000, policy.nextDelayMillis())
        assertEquals(2000, policy.nextDelayMillis())
        assertEquals(3000, policy.nextDelayMillis())
    }

    @Test
    fun `the wait never grows past the ceiling`() {
        val policy = ReconnectPolicy(baseDelayMillis = 1000, maxDelayMillis = 30000)
        var delay = 0L
        repeat(100) { delay = policy.nextDelayMillis() }
        assertEquals(30000, delay)
    }

    @Test
    fun `a working connection starts the wait over`() {
        val policy = ReconnectPolicy(baseDelayMillis = 1000, maxDelayMillis = 30000)
        policy.nextDelayMillis()
        policy.nextDelayMillis()
        policy.reset()
        assertEquals(0, policy.attempt)
        assertEquals(1000, policy.nextDelayMillis())
    }

    @Test
    fun `the wait stays inside the bounds the plan gives`() {
        val policy = ReconnectPolicy()
        var delay = policy.nextDelayMillis()
        assertTrue(delay >= 1000, "The first wait of $delay ms is too short")
        delay = policy.nextDelayMillis()
        assertTrue(delay <= 30000, "The wait of $delay ms is too long")
    }
}
