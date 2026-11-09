package com.sendspin.protocol.protocol

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * The binary audio messages.
 */
class AudioChunkTest {
    private val audio = byteArrayOf(1, 2, 3, 4, 5)

    @Test
    fun `builds a source audio message`() {
        val message = AudioChunk.source(1_234_567_890L, audio)
        assertEquals(MessageId.SOURCE_AUDIO.toByte(), message[0])
        assertEquals(1 + 8 + audio.size, message.size)
        assertArrayEquals(audio, message.copyOfRange(9, message.size))
    }

    @Test
    fun `reads a source audio message`() {
        val message = AudioChunk.source(1_234_567_890L, audio)
        val parsed = AudioChunk.parseSource(message)
        assertEquals(1_234_567_890L, parsed.timestampMicros)
        assertArrayEquals(audio, parsed.audio)
    }

    @Test
    fun `the source timestamp is big endian`() {
        val message = AudioChunk.source(1L, audio)
        // Bytes 1 to 8 hold the timestamp, with the most significant byte
        // first.
        assertEquals(0L, message[1].toLong())
        assertEquals(1L, message[8].toLong())
    }

    @Test
    fun `builds a player audio message`() {
        val message = AudioChunk.player(1_234_567_890L, 250_000L, audio)
        assertEquals(MessageId.PLAYER_AUDIO.toByte(), message[0])
        assertEquals(1 + 8 + 4 + audio.size, message.size)
        assertArrayEquals(audio, message.copyOfRange(13, message.size))
    }

    @Test
    fun `reads a player audio message`() {
        val message = AudioChunk.player(1_234_567_890L, 250_000L, audio)
        val parsed = AudioChunk.parsePlayer(message)
        assertEquals(1_234_567_890L, parsed.timestampMicros)
        assertEquals(250_000L, parsed.sendAheadMicros)
        assertArrayEquals(audio, parsed.audio)
    }

    @Test
    fun `the lead saturates rather than wrapping`() {
        // A server that transmits at or after the timestamp sends zero.
        assertEquals(0, AudioChunk.saturateSendAhead(0))
        assertEquals(0, AudioChunk.saturateSendAhead(-1000))
        assertEquals(1000, AudioChunk.saturateSendAhead(1000))
        // A lead too large for the field becomes the largest value.
        assertEquals(-1, AudioChunk.saturateSendAhead(4294967295L))
        assertEquals(-1, AudioChunk.saturateSendAhead(4294967296L))
        assertEquals(-1, AudioChunk.saturateSendAhead(Long.MAX_VALUE))
    }

    @Test
    fun `a saturated lead reads back as the largest value`() {
        val message = AudioChunk.player(0L, Long.MAX_VALUE, audio)
        val parsed = AudioChunk.parsePlayer(message)
        assertEquals(4294967295L, parsed.sendAheadMicros)
    }

    @Test
    fun `rejects a message that is too short`() {
        assertThrows(IllegalArgumentException::class.java) {
            AudioChunk.parseSource(byteArrayOf(MessageId.SOURCE_AUDIO.toByte()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AudioChunk.parsePlayer(byteArrayOf(MessageId.PLAYER_AUDIO.toByte()))
        }
    }

    @Test
    fun `rejects a message of the wrong type`() {
        val message = AudioChunk.source(0L, audio)
        assertThrows(IllegalArgumentException::class.java) {
            AudioChunk.parsePlayer(message)
        }
    }

    @Test
    fun `an empty audio frame is allowed`() {
        val message = AudioChunk.source(0L, ByteArray(0))
        assertEquals(9, message.size)
        val parsed = AudioChunk.parseSource(message)
        assertEquals(0, parsed.audio.size)
    }
}
