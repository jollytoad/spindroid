package com.sendspin.protocol.psk

import com.sendspin.protocol.crypto.Base64Url
import com.sendspin.protocol.crypto.Hashing
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The store of pre-shared keys and the choice of key for a handshake.
 */
class PskStoreTest {
    private fun psk(seed: Int): ByteArray = ByteArray(32) { (seed + it).toByte() }

    private fun record(
        serverId: String,
        seed: Int,
        category: PskCategory = PskCategory.LONG_TERM,
    ) = PskRecord(
        serverId = serverId,
        psk = psk(seed),
        serverName = "Server $serverId",
        category = category,
    )

    @Test
    fun `the sentinel psk is the published constant`() {
        assertEquals(
            "1b5e24dbc1aed95fc2a5a338a90c05df44bd10f5ec1f4cd66cbf86272767b9d3",
            SendspinPsk.SENTINEL.joinToString("") { "%02x".format(it) },
        )
        assertEquals(
            "GFsV9tLaSQm9HcFWpKsgYQOr7wFTvNUtkmFwuVz3zoo",
            SendspinPsk.SENTINEL_ID,
        )
    }

    @Test
    fun `the sentinel psk is the hash of its label`() {
        val expected = Hashing.sha256("sendspin-sentinel-psk-v1".toByteArray(Charsets.UTF_8))
        assertArrayEquals(expected, SendspinPsk.SENTINEL)
    }

    @Test
    fun `the psk identifier follows the formula`() {
        val key = psk(1)
        val expected = Base64Url.encode(
            Hashing.sha256("sendspin-psk-id-v1".toByteArray(Charsets.UTF_8) + key),
        )
        assertEquals(expected, SendspinPsk.id(key))
        assertEquals(43, SendspinPsk.id(key).length)
    }

    @Test
    fun `finds a record by its identifier`() {
        val store = InMemoryPskStore()
        val record = record("server-1", 1)
        store.put(record)

        val found = store.find(record.pskId, PskCategory.LONG_TERM)
        assertEquals(record, found)
    }

    @Test
    fun `does not find a record under the wrong category`() {
        val store = InMemoryPskStore()
        val record = record("server-1", 1, PskCategory.PAIRING)
        store.put(record)

        assertNull(store.find(record.pskId, PskCategory.LONG_TERM))
        assertEquals(record, store.find(record.pskId, PskCategory.PAIRING))
    }

    @Test
    fun `replaces the record of a server that pairs again`() {
        val store = InMemoryPskStore()
        store.put(record("server-1", 1))
        store.put(record("server-1", 2))
        assertEquals(1, store.size)
    }

    @Test
    fun `holds at least five records`() {
        val store = InMemoryPskStore()
        assertTrue(store.size == 0)
        for (index in 1..5) {
            store.put(record("server-$index", index))
        }
        assertEquals(5, store.size)
    }

    @Test
    fun `evicts the least recently used record`() {
        val store = InMemoryPskStore(capacity = 5)
        for (index in 1..5) {
            store.put(record("server-$index", index))
        }

        // Using the first record makes it the most recently used one.
        val first = record("server-1", 1)
        store.find(first.pskId, PskCategory.LONG_TERM)

        // The sixth record pushes the second one out.
        store.put(record("server-6", 6))
        assertEquals(5, store.size)

        assertNull(store.find(record("server-2", 2).pskId, PskCategory.LONG_TERM))
        assertEquals(record("server-1", 1), store.find(first.pskId, PskCategory.LONG_TERM))
        assertEquals(record("server-6", 6), store.find(record("server-6", 6).pskId, PskCategory.LONG_TERM))
    }

    @Test
    fun `never evicts a record that backs an open connection`() {
        val store = InMemoryPskStore(capacity = 5)
        for (index in 1..5) {
            store.put(record("server-$index", index))
        }

        // The first three records back open connections.
        for (index in 1..3) {
            store.markInUse("server-$index")
        }

        // Two more records push the store past its capacity. The records
        // that back a connection stay, so the other two are evicted.
        store.put(record("server-6", 6))
        store.put(record("server-7", 7))

        assertEquals(5, store.size)
        for (index in 1..3) {
            assertEquals(
                record("server-$index", index),
                store.find(record("server-$index", index).pskId, PskCategory.LONG_TERM),
            )
        }
        assertEquals(
            record("server-7", 7),
            store.find(record("server-7", 7).pskId, PskCategory.LONG_TERM),
        )
    }

    @Test
    fun `a record becomes evictable again when it is released`() {
        val store = InMemoryPskStore(capacity = 5)
        for (index in 1..5) {
            store.put(record("server-$index", index))
        }
        store.markInUse("server-1")
        store.markUnused("server-1")

        store.put(record("server-6", 6))
        assertNull(store.find(record("server-1", 1).pskId, PskCategory.LONG_TERM))
    }

    @Test
    fun `removes a record`() {
        val store = InMemoryPskStore()
        store.put(record("server-1", 1))
        store.remove("server-1")
        assertEquals(0, store.size)
        assertNull(store.find(record("server-1", 1).pskId, PskCategory.LONG_TERM))
    }
}

/**
 * The choice of key for a handshake.
 */
class PskSelectorTest {
    private fun psk(seed: Int): ByteArray = ByteArray(32) { (seed + it).toByte() }

    private fun record(serverId: String, seed: Int) = PskRecord(
        serverId = serverId,
        psk = psk(seed),
        serverName = "Server $serverId",
        category = PskCategory.LONG_TERM,
    )

    @Test
    fun `finds the key the server named`() {
        val store = InMemoryPskStore()
        val record = record("server-1", 1)
        store.put(record)

        val selection = PskSelector(store).select(record.pskId, PskCategory.LONG_TERM, false)
        assertTrue(selection is PskSelection.Found)
        assertArrayEquals(record.psk, selection.psk)
    }

    @Test
    fun `falls back to the sentinel on a miss in the first handshake`() {
        val store = InMemoryPskStore()
        val selection = PskSelector(store).select("no-such-key", PskCategory.LONG_TERM, false)
        assertTrue(selection is PskSelection.SentinelFallback)
        assertArrayEquals(SendspinPsk.SENTINEL, selection.psk)
    }

    @Test
    fun `fails on a miss in a re-handshake`() {
        val store = InMemoryPskStore()
        val selection = PskSelector(store).select("no-such-key", PskCategory.LONG_TERM, true)
        assertTrue(selection is PskSelection.Failure)
    }

    @Test
    fun `the sentinel fallback names the key that was missing`() {
        val store = InMemoryPskStore()
        val selection = PskSelector(store).select("no-such-key", PskCategory.LONG_TERM, false)
        assertEquals("no-such-key", (selection as PskSelection.SentinelFallback).requestedId)
    }

    @Test
    fun `a key held under another category is a miss`() {
        val store = InMemoryPskStore()
        val record = record("server-1", 1).copy(category = PskCategory.PAIRING)
        store.put(record)

        val selection = PskSelector(store).select(record.pskId, PskCategory.LONG_TERM, false)
        assertTrue(selection is PskSelection.SentinelFallback)
    }
}
