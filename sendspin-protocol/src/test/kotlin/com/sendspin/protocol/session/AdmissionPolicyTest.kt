package com.sendspin.protocol.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The rule that decides which connection the client keeps.
 */
class AdmissionPolicyTest {
    private var lastPlayback: String? = null
    private val policy = AdmissionPolicy { lastPlayback }

    private fun holder(
        serverId: String,
        activities: Set<Activity>,
        pairingAttempt: Boolean = false,
    ) = AdmissionPolicy.Holder(serverId, activities, pairingAttempt)

    @Test
    fun `the first connection is always admitted`() {
        assertTrue(
            policy.admits(
                holder = null,
                incomingServerId = "server-1",
                incomingActivities = emptySet(),
                incomingPairingAttempt = false,
            ),
        )
    }

    @Test
    fun `a connection that plays audio beats an idle one`() {
        assertTrue(
            policy.admits(
                holder = holder("server-1", emptySet()),
                incomingServerId = "server-2",
                incomingActivities = setOf(Activity.PLAYBACK),
                incomingPairingAttempt = false,
            ),
        )
    }

    @Test
    fun `a connection that pairs beats an idle one`() {
        assertTrue(
            policy.admits(
                holder = holder("server-1", emptySet()),
                incomingServerId = "server-2",
                incomingActivities = setOf(Activity.PAIRING),
                incomingPairingAttempt = false,
            ),
        )
    }

    @Test
    fun `a connection that plays audio beats one that pairs`() {
        assertTrue(
            policy.admits(
                holder = holder("server-1", setOf(Activity.PAIRING)),
                incomingServerId = "server-2",
                incomingActivities = setOf(Activity.PLAYBACK),
                incomingPairingAttempt = false,
            ),
        )
    }

    @Test
    fun `a connection that pairs does not beat one that plays audio`() {
        assertFalse(
            policy.admits(
                holder = holder("server-1", setOf(Activity.PLAYBACK)),
                incomingServerId = "server-2",
                incomingActivities = setOf(Activity.PAIRING),
                incomingPairingAttempt = false,
            ),
        )
    }

    @Test
    fun `a connection of the same rank takes over`() {
        assertTrue(
            policy.admits(
                holder = holder("server-1", setOf(Activity.PLAYBACK)),
                incomingServerId = "server-2",
                incomingActivities = setOf(Activity.PLAYBACK),
                incomingPairingAttempt = false,
            ),
        )
    }

    @Test
    fun `a pairing attempt is not displaced`() {
        assertFalse(
            policy.admits(
                holder = holder("server-1", setOf(Activity.PAIRING), pairingAttempt = true),
                incomingServerId = "server-2",
                incomingActivities = setOf(Activity.PLAYBACK),
                incomingPairingAttempt = false,
            ),
        )
    }

    @Test
    fun `two idle servers keep the one the client played for last`() {
        lastPlayback = "server-2"
        assertTrue(
            policy.admits(
                holder = holder("server-1", emptySet()),
                incomingServerId = "server-2",
                incomingActivities = emptySet(),
                incomingPairingAttempt = false,
            ),
        )
    }

    @Test
    fun `two idle servers keep the current one when it played last`() {
        lastPlayback = "server-1"
        assertFalse(
            policy.admits(
                holder = holder("server-1", emptySet()),
                incomingServerId = "server-2",
                incomingActivities = emptySet(),
                incomingPairingAttempt = false,
            ),
        )
    }

    @Test
    fun `two idle servers keep the current one when neither played last`() {
        lastPlayback = null
        assertFalse(
            policy.admits(
                holder = holder("server-1", emptySet()),
                incomingServerId = "server-2",
                incomingActivities = emptySet(),
                incomingPairingAttempt = false,
            ),
        )
    }

    @Test
    fun `the activities rank as the specification says`() {
        assertEquals(RANK_PLAYBACK, rank(setOf(Activity.PLAYBACK)))
        assertEquals(RANK_PAIRING, rank(setOf(Activity.PAIRING)))
        assertEquals(RANK_NONE, rank(emptySet()))
        // The higher purpose wins when a connection declares both.
        assertEquals(RANK_PLAYBACK, rank(setOf(Activity.PLAYBACK, Activity.PAIRING)))
    }

    @Test
    fun `the activities read their wire names`() {
        assertEquals(Activity.PLAYBACK, Activity.fromWireName("playback"))
        assertEquals(Activity.PAIRING, Activity.fromWireName("pairing"))
        assertEquals(null, Activity.fromWireName("something else"))
    }
}
