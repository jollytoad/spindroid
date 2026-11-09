package com.sendspin.protocol.pairing

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The pairing token, against the reference vectors of the specification.
 */
class PairingTokenTest {
    private fun bytes(start: Int, end: Int): ByteArray =
        ByteArray(end - start) { (start + it).toByte() }

    @Test
    fun `encodes the reference vector for a pairing psk`() {
        val token = PairingToken.encodePairingPsk(
            clientKey = bytes(0x00, 0x20),
            pairingPsk = bytes(0xE0, 0x100),
        )
        assertEquals(
            "SP:0AAAQEAYEAUDAOCAJBIFQYDIOB4IBCEQTCQKRMFYYDENBWHA5DYP6BYPC4PSOLZXH5DU6V97M5XXO74HR6LZ7J5PW674PT6X37T6757Y",
            token,
        )
    }

    @Test
    fun `encodes the reference vector for a dynamic pairing code`() {
        val token = PairingToken.encodeDynamicCode(bytes(0xE0, 0xF8))
        assertEquals("SP:14DQ6FY7E4XTOP9HJ5LV6Z3PO57YPD4XT6T97N5Y", token)
    }

    @Test
    fun `decodes the reference vector for a pairing psk`() {
        val token = PairingToken.decode(
            "SP:0AAAQEAYEAUDAOCAJBIFQYDIOB4IBCEQTCQKRMFYYDENBWHA5DYP6BYPC4PSOLZXH5DU6V97M5XXO74HR6LZ7J5PW674PT6X37T6757Y",
        )
        assertTrue(token is PairingToken.Token.PairingPsk)
        token as PairingToken.Token.PairingPsk
        assertArrayEquals(bytes(0x00, 0x20), token.clientKey)
        assertArrayEquals(bytes(0xE0, 0x100), token.pairingPsk)
    }

    @Test
    fun `decodes the reference vector for a dynamic pairing code`() {
        val token = PairingToken.decode("SP:14DQ6FY7E4XTOP9HJ5LV6Z3PO57YPD4XT6T97N5Y")
        assertTrue(token is PairingToken.Token.DynamicCode)
        token as PairingToken.Token.DynamicCode
        assertArrayEquals(bytes(0xE0, 0xF8), token.code)
    }

    @Test
    fun `round trips both versions`() {
        val clientKey = bytes(0x00, 0x20)
        val pairingPsk = bytes(0xE0, 0x100)
        val decoded = PairingToken.decode(
            PairingToken.encodePairingPsk(clientKey, pairingPsk),
        ) as PairingToken.Token.PairingPsk
        assertArrayEquals(clientKey, decoded.clientKey)
        assertArrayEquals(pairingPsk, decoded.pairingPsk)

        val code = bytes(0xE0, 0xF8)
        val decodedCode = PairingToken.decode(
            PairingToken.encodeDynamicCode(code),
        ) as PairingToken.Token.DynamicCode
        assertArrayEquals(code, decodedCode.code)
    }

    @Test
    fun `accepts the input an operator types`() {
        val expected = "SP:0AAAQEAYEAUDAOCAJBIFQYDIOB4IBCEQTCQKRMFYYDENBWHA5DYP6BYPC4PSOLZXH5DU6V97M5XXO74HR6LZ7J5PW674PT6X37T6757Y"
        // The prefix is optional, and the case does not matter.
        assertEquals(
            expected,
            PairingToken.encodePairingPsk(bytes(0x00, 0x20), bytes(0xE0, 0x100)),
        )
        assertTrue(
            PairingToken.decode(expected.removePrefix("SP:").lowercase()) is
                PairingToken.Token.PairingPsk,
        )
        assertTrue(
            PairingToken.decode("  $expected  ") is PairingToken.Token.PairingPsk,
        )
    }

    @Test
    fun `rejects an unknown version`() {
        assertNull(PairingToken.decode("SP:2AAAQEAYEAUDAOCAJBIFQYDIOB4IBCEQTCQKRMFYYDENB"))
        assertNull(PairingToken.decode("SP:AAAQEAYEAUDAOCAJBIFQYDIOB4IBCEQTCQKRMFYYDENB"))
    }

    @Test
    fun `rejects a body that is too short`() {
        // A version 0 token carries 64 bytes, which need 103 characters.
        assertNull(PairingToken.decode("SP:0MZXW6YTBOI"))
        // A version 1 token carries 24 bytes, which need 39 characters.
        assertNull(PairingToken.decode("SP:1MZXW6YTBOI"))
    }

    @Test
    fun `rejects a body that is not base32`() {
        assertNull(PairingToken.decode("SP:0MZXW6YTBO1"))
        assertNull(PairingToken.decode("SP:0MZXW6YTBO!"))
    }

    @Test
    fun `rejects an empty token`() {
        assertNull(PairingToken.decode(""))
        assertNull(PairingToken.decode("SP:"))
    }
}
