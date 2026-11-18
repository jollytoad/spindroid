package app.gubbins.sendspin.crypto

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * X25519, against the test vectors of RFC 7748.
 */
class X25519Test {
    private fun hex(s: String): ByteArray {
        require(s.length % 2 == 0) { "A hex string has an even length" }
        return ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun assertHex(expected: String, actual: ByteArray) {
        assertEquals(expected, actual.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `matches the RFC 7748 vector`() {
        val scalar = hex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4")
        val u = hex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c")
        val expected = "c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552"

        assertHex(expected, X25519.sharedSecret(scalar, u))
    }

    @Test
    fun `matches the second RFC 7748 vector`() {
        val scalar = hex("4b66e9d4d1b4673c5ad22691957d6af5c11b6421e0ea01d42ca4169e7918ba0d")
        val u = hex("e5210f12786811d3f4b7959d0538ae2c31dbe7106fc03c3efc4cd549c715a493")
        val expected = "95cbde9476e8907d7aade45cb4b873f88b595a68799fa152e6f8f7647aac7957"

        assertHex(expected, X25519.sharedSecret(scalar, u))
    }

    @Test
    fun `matches the RFC 7748 iteration vector`() {
        // The specification starts with the base point and repeats the
        // function, using each result as the next scalar.
        var scalar = hex("0900000000000000000000000000000000000000000000000000000000000000")
        val u = hex("0900000000000000000000000000000000000000000000000000000000000000")
        val expected = "422c8e7a6227d7bca1350b3e2bb7279f7897b87bb6854b783c60e80311ae3079"

        val result = X25519.sharedSecret(scalar, u)
        assertHex(expected, result)
        // The shared secret with the base point is the public key, so the
        // iteration is the same as deriving the public key of the result.
        assertHex(expected, X25519.publicKey(scalar))
    }

    @Test
    fun `derives the public key of a known private key`() {
        // The RFC 7748 section 6.1 exchange.
        val alicePrivate = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val alicePublic = "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a"
        val bobPrivate = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        val bobPublic = "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f"
        val shared = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"

        assertHex(alicePublic, X25519.publicKey(alicePrivate))
        assertHex(bobPublic, X25519.publicKey(bobPrivate))
        assertHex(shared, X25519.sharedSecret(alicePrivate, hex(bobPublic)))
        assertHex(shared, X25519.sharedSecret(bobPrivate, hex(alicePublic)))
    }

    @Test
    fun `generates a usable key pair`() {
        val privateKey = X25519.generatePrivateKey()
        assertEquals(X25519.KEY_SIZE, privateKey.size)

        val publicKey = X25519.publicKey(privateKey)
        assertEquals(X25519.KEY_SIZE, publicKey.size)

        // The public key must be the same every time.
        assertHex(
            publicKey.joinToString("") { "%02x".format(it) },
            X25519.publicKey(privateKey),
        )
    }

    @Test
    fun `generates a different key pair every time`() {
        val first = X25519.generatePrivateKey()
        val second = X25519.generatePrivateKey()
        assertNotEquals(
            first.joinToString("") { "%02x".format(it) },
            second.joinToString("") { "%02x".format(it) },
        )
    }

    @Test
    fun `both sides reach the same shared secret`() {
        val alice = X25519.generatePrivateKey()
        val bob = X25519.generatePrivateKey()
        val alicePublic = X25519.publicKey(alice)
        val bobPublic = X25519.publicKey(bob)

        assertArrayEquals(
            X25519.sharedSecret(alice, bobPublic),
            X25519.sharedSecret(bob, alicePublic),
        )
    }
}
