package com.sendspin.protocol.protocol

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The JSON message envelope.
 */
class MessageCodecTest {
    private val codec = MessageCodec()

    @Test
    fun `round trips a message`() {
        val payload = ClientInitPayload(
            clientId = "abc123",
            version = 1,
            suite = "25519_ChaChaPoly_SHA256",
        )
        val bytes = codec.encode(MessageType.CLIENT_INIT, payload)
        val decoded = codec.decode<ClientInitPayload>(MessageType.CLIENT_INIT, bytes)

        assertEquals(payload.clientId, decoded.clientId)
        assertEquals(payload.version, decoded.version)
        assertEquals(payload.suite, decoded.suite)
    }

    @Test
    fun `keeps a microsecond timestamp exact`() {
        // A timestamp near the end of the range would lose its last digits
        // if the codec read it as a double.
        val payload = ClientTimePayload(clientTransmitted = 9_007_199_254_740_993L)
        val bytes = codec.encode(MessageType.CLIENT_TIME, payload)
        val decoded = codec.decode<ClientTimePayload>(MessageType.CLIENT_TIME, bytes)
        assertEquals(9_007_199_254_740_993L, decoded.clientTransmitted)
    }

    @Test
    fun `keeps a negative timestamp exact`() {
        val payload = ServerTimePayload(
            clientTransmitted = -1L,
            serverReceived = -9_007_199_254_740_993L,
            serverTransmitted = 0L,
        )
        val bytes = codec.encode(MessageType.SERVER_TIME, payload)
        val decoded = codec.decode<ServerTimePayload>(MessageType.SERVER_TIME, bytes)
        assertEquals(-1L, decoded.clientTransmitted)
        assertEquals(-9_007_199_254_740_993L, decoded.serverReceived)
        assertEquals(0L, decoded.serverTransmitted)
    }

    @Test
    fun `writes the same bytes every time`() {
        // The handshake binds the prologue to the exact bytes of the init
        // messages, so the encoder must be stable.
        val payload = ClientInitPayload("abc123", 1, "25519_AESGCM_SHA256")
        val first = codec.encode(MessageType.CLIENT_INIT, payload)
        val second = codec.encode(MessageType.CLIENT_INIT, payload)
        assertArrayEquals(first, second)
    }

    @Test
    fun `reads the type without the payload`() {
        val bytes = codec.encode(
            MessageType.SERVER_HELLO,
            ServerHelloPayload(name = "Living Room"),
        )
        assertEquals(MessageType.SERVER_HELLO, codec.typeOf(bytes))
    }

    @Test
    fun `rejects a message of a different type`() {
        val bytes = codec.encode(
            MessageType.SERVER_HELLO,
            ServerHelloPayload(name = "Living Room"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            codec.decode<ClientHelloPayload>(MessageType.CLIENT_HELLO, bytes)
        }
    }

    @Test
    fun `rejects a message with no payload type`() {
        assertThrows(IllegalArgumentException::class.java) {
            codec.encode(MessageType.CLIENT_INIT, "not a payload")
        }
    }

    @Test
    fun `rejects a message that is not valid json`() {
        assertThrows(IllegalArgumentException::class.java) {
            codec.decode<ServerHelloPayload>(
                MessageType.SERVER_HELLO,
                "not json".toByteArray(),
            )
        }
    }

    @Test
    fun `keeps an optional field absent`() {
        val bytes = codec.encode(
            MessageType.SERVER_HELLO,
            ServerHelloPayload(name = "Living Room"),
        )
        val text = String(bytes, Charsets.UTF_8)
        assertTrue(!text.contains("languages"), "The message is $text")
        assertTrue(!text.contains("source@v1_support"), "The message is $text")
    }

    @Test
    fun `reads a nested object`() {
        val payload = ServerActivatePayload(
            activities = listOf("pairing"),
            activeRoles = listOf("source@v1"),
            pairing = PairingParameters(method = "pairing_psk"),
        )
        val bytes = codec.encode(MessageType.SERVER_ACTIVATE, payload)
        val decoded = codec.decode<ServerActivatePayload>(MessageType.SERVER_ACTIVATE, bytes)
        assertEquals(listOf("pairing"), decoded.activities)
        assertEquals(listOf("source@v1"), decoded.activeRoles)
        assertEquals("pairing_psk", decoded.pairing?.method)
    }
}
