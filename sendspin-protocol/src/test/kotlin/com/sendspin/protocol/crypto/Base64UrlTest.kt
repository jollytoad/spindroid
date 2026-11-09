package com.sendspin.protocol.crypto

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class Base64UrlTest {
    @Test
    fun `encodes without padding`() {
        assertEquals("", Base64Url.encode(ByteArray(0)))
        assertEquals("AQ", Base64Url.encode(byteArrayOf(1)))
        assertEquals("AQI", Base64Url.encode(byteArrayOf(1, 2)))
        assertEquals("AQID", Base64Url.encode(byteArrayOf(1, 2, 3)))
        assertEquals("AQIDBA", Base64Url.encode(byteArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun `uses the url alphabet`() {
        // The bytes that encode to the two characters that differ between the
        // standard and the URL alphabets.
        assertEquals("__8", Base64Url.encode(byteArrayOf(0xFF.toByte(), 0xFF.toByte())))
        assertEquals("-_8", Base64Url.encode(byteArrayOf(0xFB.toByte(), 0xFF.toByte())))
    }

    @Test
    fun `round trips every length up to 64 bytes`() {
        for (length in 0..64) {
            val bytes = ByteArray(length) { (it * 7 + 3).toByte() }
            assertArrayEquals(bytes, Base64Url.decode(Base64Url.encode(bytes)))
        }
    }

    @Test
    fun `decodes a known value`() {
        assertArrayEquals(
            byteArrayOf(0xFF.toByte(), 0xE0.toByte(), 0x6D.toByte()),
            Base64Url.decode("_-Bt"),
        )
    }

    @Test
    fun `rejects padding`() {
        assertNull(Base64Url.decode("AQ=="))
    }

    @Test
    fun `rejects the standard alphabet`() {
        assertNull(Base64Url.decode("AB+/"))
    }

    @Test
    fun `rejects an impossible length`() {
        assertNull(Base64Url.decode("A"))
    }

    @Test
    fun `rejects non canonical trailing bits`() {
        // "AB" would decode to the same byte as "AA", so it is not canonical.
        assertArrayEquals(byteArrayOf(0), Base64Url.decode("AA"))
        assertNull(Base64Url.decode("AB"))
    }
}
