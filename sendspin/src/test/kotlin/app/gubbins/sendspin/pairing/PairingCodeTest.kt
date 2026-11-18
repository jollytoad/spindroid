package app.gubbins.sendspin.pairing

import app.gubbins.sendspin.crypto.Aead
import app.gubbins.sendspin.crypto.NoiseSuite
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * The pairing code, the commitment and the sealing.
 *
 * The values come from the reference implementation that the reference
 * server depends on, so a change in either side shows up here.
 */
class PairingCodeTest {
    @Test
    fun `the digest matches the reference`() {
        assertArrayEquals(
            hex("9f26c48e69443b7fab8d1be2052017a0f69b4a1f0e49033d3ab2a2328f650989"),
            PairingCode.deriveDigest(HANDSHAKE_HASH, NONCE_A, NONCE_B),
        )
    }

    @Test
    fun `the six digits match the reference`() {
        assertEquals("806729", PairingCode.deriveDigits(HANDSHAKE_HASH, NONCE_A, NONCE_B))
    }

    @Test
    fun `the code always has six digits`() {
        // A digest that reduces to a small number still gets its leading
        // zeros, or the operator would type a code of the wrong length.
        for (index in 0 until 200) {
            val nonceA = ByteArray(32) { it.toByte() }
            val nonceB = ByteArray(32) { (it + index).toByte() }
            val code = PairingCode.deriveDigits(HANDSHAKE_HASH, nonceA, nonceB)
            assertEquals(6, code.length)
            assertEquals(code, code.filter { it.isDigit() })
        }
    }

    @Test
    fun `the commitment matches the reference`() {
        assertArrayEquals(
            hex("61412db5f73a8110347d5df6d156121a37b3b23c35198c6e875dcf586b8ba6e5"),
            PairingCode.commit(NONCE_A),
        )
        assertArrayEquals(
            hex("f6d431646ea17a4488f6008d0da4dbd79b7c0a898c3631453fb6ffc912d29c18"),
            PairingCode.commit(NONCE_B),
        )
    }

    @Test
    fun `a different nonce gives a different commitment`() {
        assertNotEquals(
            PairingCode.commit(NONCE_A).toList(),
            PairingCode.commit(NONCE_B).toList(),
        )
    }

    @Test
    fun `the session id matches the reference`() {
        assertArrayEquals(
            hex("73656e647370696e2d706169722d70616b652d7631000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f0000000100000001"),
            PairingWrap.sessionId(HANDSHAKE_HASH, 1, 1),
        )
    }

    @Test
    fun `a different round gives a different session id`() {
        assertNotEquals(
            PairingWrap.sessionId(HANDSHAKE_HASH, 1, 1).toList(),
            PairingWrap.sessionId(HANDSHAKE_HASH, 1, 2).toList(),
        )
    }

    @Test
    fun `the code is grouped for the screen`() {
        assertEquals("123-456", PairingCode.group("123456"))
        assertEquals("12345", PairingCode.group("12345"))
    }

    @Test
    fun `the two wrap keys are not the same`() {
        val isk = ByteArray(64) { it.toByte() }
        val sid = PairingWrap.sessionId(HANDSHAKE_HASH, 1, 1)
        assertNotEquals(
            PairingWrap.wrapKey(PairingWrap.PSK_LABEL, sid, isk).toList(),
            PairingWrap.wrapKey(PairingWrap.NONCE_LABEL, sid, isk).toList(),
        )
    }

    @Test
    fun `a sealed value opens again`() {
        val key = PairingWrap.wrapKey(PairingWrap.PSK_LABEL, ByteArray(16), ByteArray(64))
        val secret = ByteArray(32) { (it * 7).toByte() }
        val sealed = PairingWrap.seal(NoiseSuite.AES_GCM_SHA256, key, secret)
        assertEquals(48, sealed.size)
        val opened = Aead.open(
            NoiseSuite.AES_GCM_SHA256,
            key,
            ByteArray(Aead.NONCE_SIZE),
            ByteArray(0),
            sealed,
        )
        assertArrayEquals(secret, opened)
    }

    @Test
    fun `a sealed value does not open under the other key`() {
        val sid = ByteArray(16)
        val isk = ByteArray(64)
        val sealed = PairingWrap.seal(
            NoiseSuite.AES_GCM_SHA256,
            PairingWrap.wrapKey(PairingWrap.PSK_LABEL, sid, isk),
            ByteArray(32),
        )
        val failure = runCatching {
            Aead.open(
                NoiseSuite.AES_GCM_SHA256,
                PairingWrap.wrapKey(PairingWrap.NONCE_LABEL, sid, isk),
                ByteArray(Aead.NONCE_SIZE),
                ByteArray(0),
                sealed,
            )
        }
        assertEquals(true, failure.isFailure)
    }

    @Test
    fun `the other cipher suite seals the same way`() {
        val key = PairingWrap.wrapKey(PairingWrap.PSK_LABEL, ByteArray(16), ByteArray(64))
        val secret = ByteArray(32) { it.toByte() }
        val sealed = PairingWrap.seal(NoiseSuite.CHACHA_POLY_SHA256, key, secret)
        val opened = Aead.open(
            NoiseSuite.CHACHA_POLY_SHA256,
            key,
            ByteArray(Aead.NONCE_SIZE),
            ByteArray(0),
            sealed,
        )
        assertArrayEquals(secret, opened)
    }

    private fun hex(value: String): ByteArray {
        val out = ByteArray(value.length / 2)
        for (index in out.indices) {
            out[index] = value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    private companion object {
        val HANDSHAKE_HASH = ByteArray(32) { it.toByte() }
        val NONCE_A = ByteArray(32) { (it + 32).toByte() }
        val NONCE_B = ByteArray(32) { (it + 64).toByte() }
    }
}
