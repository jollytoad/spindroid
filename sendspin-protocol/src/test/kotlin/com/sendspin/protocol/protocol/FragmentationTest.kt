package com.sendspin.protocol.protocol

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The binary framing layer.
 *
 * A Noise message holds at most 65535 bytes, so a larger message is split
 * across several messages and put back together on the other side.
 */
class FragmentationTest {
    private fun payload(size: Int): ByteArray =
        ByteArray(size) { (it % 251).toByte() }

    @Test
    fun `a small message is not fragmented`() {
        val fragments = Fragmenter.fragment(MessageId.SOURCE_AUDIO, payload(100))
        assertEquals(1, fragments.size)
        assertEquals(MessageId.SOURCE_AUDIO.toByte(), fragments[0][0])
    }

    @Test
    fun `a message at the limit is not fragmented`() {
        val body = payload(Fragmentation.MAX_PAYLOAD_SIZE)
        val fragments = Fragmenter.fragment(MessageId.SOURCE_AUDIO, body)
        assertEquals(1, fragments.size)
        assertEquals(Fragmentation.MAX_PAYLOAD_SIZE + 1, fragments[0].size)
    }

    @Test
    fun `a large message is split and put back together`() {
        val body = payload(Fragmentation.MAX_PAYLOAD_SIZE * 2 + 17)
        val fragments = Fragmenter.fragment(MessageId.SOURCE_AUDIO, body)

        assertTrue(fragments.size > 1)
        for (fragment in fragments) {
            assertEquals(MessageId.FRAGMENTATION.toByte(), fragment[0])
            // Every fragment fits in a Noise message.
            assertTrue(fragment.size <= Fragmentation.MAX_PAYLOAD_SIZE + 2)
        }

        val reassembler = FragmentReassembler()
        var result: FrameResult.Complete? = null
        for (fragment in fragments) {
            val outcome = reassembler.offer(fragment)
            if (outcome is FrameResult.Complete) result = outcome
            else assertTrue(outcome is FrameResult.Incomplete)
        }

        assertEquals(MessageId.SOURCE_AUDIO, result?.messageId)
        assertArrayEquals(body, result?.payload)
        assertFalse(reassembler.hasMessageInFlight)
    }

    @Test
    fun `the first fragment carries the original type`() {
        val body = payload(Fragmentation.MAX_PAYLOAD_SIZE + 10)
        val fragments = Fragmenter.fragment(MessageId.SOURCE_AUDIO, body)

        val first = fragments[0]
        assertEquals(MessageId.FRAGMENTATION.toByte(), first[0])
        assertEquals(
            (Fragmentation.FLAG_FIRST).toByte(),
            first[1],
        )
        assertEquals(MessageId.SOURCE_AUDIO.toByte(), first[2])

        // The later fragments do not carry the type again, so they hold one
        // byte more of the payload than the first one does.
        assertEquals(Fragmentation.MAX_PAYLOAD_SIZE + 1, first.size)
        for (fragment in fragments.drop(1)) {
            assertTrue(fragment.size <= Fragmentation.MAX_PAYLOAD_SIZE + 1)
        }
        // The last fragment holds the remainder of the body.
        assertEquals(
            Fragmentation.MAX_PAYLOAD_SIZE + 10 - (Fragmentation.MAX_PAYLOAD_SIZE - 2),
            fragments.last().size - 2,
        )
    }

    @Test
    fun `the last fragment carries the last flag`() {
        val body = payload(Fragmentation.MAX_PAYLOAD_SIZE + 10)
        val fragments = Fragmenter.fragment(MessageId.SOURCE_AUDIO, body)

        val last = fragments.last()
        assertEquals((Fragmentation.FLAG_LAST).toByte(), last[1])
        for (fragment in fragments.dropLast(1)) {
            assertEquals(0, fragment[1].toInt() and Fragmentation.FLAG_LAST)
        }
    }

    @Test
    fun `a plain message passes straight through`() {
        val reassembler = FragmentReassembler()
        val message = byteArrayOf(MessageId.SOURCE_AUDIO.toByte(), 1, 2, 3)
        val result = reassembler.offer(message)
        assertTrue(result is FrameResult.Complete)
        result as FrameResult.Complete
        assertEquals(MessageId.SOURCE_AUDIO, result.messageId)
        assertArrayEquals(byteArrayOf(1, 2, 3), result.payload)
    }

    @Test
    fun `a continuation without a first fragment is malformed`() {
        val reassembler = FragmentReassembler()
        val fragment = byteArrayOf(
            MessageId.FRAGMENTATION.toByte(),
            Fragmentation.FLAG_LAST.toByte(),
            1,
        )
        val result = reassembler.offer(fragment)
        assertTrue(result is FrameResult.Malformed)
    }

    @Test
    fun `a first fragment during another message is malformed`() {
        val reassembler = FragmentReassembler()
        val first = byteArrayOf(
            MessageId.FRAGMENTATION.toByte(),
            Fragmentation.FLAG_FIRST.toByte(),
            MessageId.SOURCE_AUDIO.toByte(),
            1,
        )
        assertTrue(reassembler.offer(first) is FrameResult.Incomplete)

        val second = byteArrayOf(
            MessageId.FRAGMENTATION.toByte(),
            Fragmentation.FLAG_FIRST.toByte(),
            MessageId.SOURCE_AUDIO.toByte(),
            2,
        )
        assertTrue(reassembler.offer(second) is FrameResult.Malformed)
    }

    @Test
    fun `a plain message during a fragmented message is malformed`() {
        val reassembler = FragmentReassembler()
        val first = byteArrayOf(
            MessageId.FRAGMENTATION.toByte(),
            Fragmentation.FLAG_FIRST.toByte(),
            MessageId.SOURCE_AUDIO.toByte(),
            1,
        )
        assertTrue(reassembler.offer(first) is FrameResult.Incomplete)
        val plain = byteArrayOf(MessageId.SOURCE_AUDIO.toByte(), 2)
        assertTrue(reassembler.offer(plain) is FrameResult.Malformed)
    }

    @Test
    fun `a reserved flag bit is malformed`() {
        val reassembler = FragmentReassembler()
        val fragment = byteArrayOf(
            MessageId.FRAGMENTATION.toByte(),
            (Fragmentation.FLAG_FIRST or 0b1000_0000).toByte(),
            MessageId.SOURCE_AUDIO.toByte(),
            1,
        )
        assertTrue(reassembler.offer(fragment) is FrameResult.Malformed)
    }

    @Test
    fun `a fragment of a fragment is malformed`() {
        val reassembler = FragmentReassembler()
        val fragment = byteArrayOf(
            MessageId.FRAGMENTATION.toByte(),
            Fragmentation.FLAG_FIRST.toByte(),
            MessageId.FRAGMENTATION.toByte(),
            1,
        )
        assertTrue(reassembler.offer(fragment) is FrameResult.Malformed)
    }

    @Test
    fun `an empty message is malformed`() {
        val reassembler = FragmentReassembler()
        assertTrue(reassembler.offer(ByteArray(0)) is FrameResult.Malformed)
    }

    @Test
    fun `a fragment without a flags byte is malformed`() {
        val reassembler = FragmentReassembler()
        val fragment = byteArrayOf(MessageId.FRAGMENTATION.toByte())
        assertTrue(reassembler.offer(fragment) is FrameResult.Malformed)
    }

    @Test
    fun `a first fragment without a type is malformed`() {
        val reassembler = FragmentReassembler()
        val fragment = byteArrayOf(
            MessageId.FRAGMENTATION.toByte(),
            Fragmentation.FLAG_FIRST.toByte(),
        )
        assertTrue(reassembler.offer(fragment) is FrameResult.Malformed)
    }

    @Test
    fun `a reset forgets the partial message`() {
        val reassembler = FragmentReassembler()
        val first = byteArrayOf(
            MessageId.FRAGMENTATION.toByte(),
            Fragmentation.FLAG_FIRST.toByte(),
            MessageId.SOURCE_AUDIO.toByte(),
            1,
        )
        assertTrue(reassembler.offer(first) is FrameResult.Incomplete)
        assertTrue(reassembler.hasMessageInFlight)

        reassembler.reset()
        assertFalse(reassembler.hasMessageInFlight)

        // A continuation now fails, because nothing is in flight.
        val continuation = byteArrayOf(
            MessageId.FRAGMENTATION.toByte(),
            Fragmentation.FLAG_LAST.toByte(),
            2,
        )
        assertTrue(reassembler.offer(continuation) is FrameResult.Malformed)
    }
}
