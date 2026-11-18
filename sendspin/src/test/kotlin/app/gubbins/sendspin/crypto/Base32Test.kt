package app.gubbins.sendspin.crypto

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class Base32Test {
    @Test
    fun `matches the RFC 4648 vectors`() {
        assertEquals("", Base32.encode(ByteArray(0)))
        assertEquals("MY", Base32.encode("f".toByteArray()))
        assertEquals("MZXQ", Base32.encode("fo".toByteArray()))
        assertEquals("MZXW6", Base32.encode("foo".toByteArray()))
        assertEquals("MZXW6YQ", Base32.encode("foob".toByteArray()))
        assertEquals("MZXW6YTB", Base32.encode("fooba".toByteArray()))
        assertEquals("MZXW6YTBOI", Base32.encode("foobar".toByteArray()))
    }

    @Test
    fun `round trips every length up to 64 bytes`() {
        for (length in 0..64) {
            val bytes = ByteArray(length) { (it * 11 + 5).toByte() }
            assertArrayEquals(bytes, Base32.decode(Base32.encode(bytes)))
        }
    }

    @Test
    fun `decodes a padded value`() {
        assertArrayEquals("foobar".toByteArray(), Base32.decode("MZXW6YTBOI======"))
    }

    @Test
    fun `rejects a character outside the alphabet`() {
        assertNull(Base32.decode("MZXW6YTB0I"))
        assertNull(Base32.decode("MZXW6YTB1I"))
        assertNull(Base32.decode("MZXW6YTB8I"))
    }

    @Test
    fun `rejects an impossible length`() {
        assertNull(Base32.decode("MZXW6YTB8"))
    }

    @Test
    fun `rejects non canonical trailing bits`() {
        // "MZXQ" is the canonical encoding of "fo". "MZXR" holds the same
        // bytes, but its last character carries bits that are not part of the
        // data, so two different strings would decode to the same bytes.
        assertArrayEquals("fo".toByteArray(), Base32.decode("MZXQ"))
        assertNull(Base32.decode("MZXR"))

        // "MZ" would decode to the same byte as "MY".
        assertNull(Base32.decode("MZ"))
        assertArrayEquals("f".toByteArray(), Base32.decode("MY"))
    }
}
