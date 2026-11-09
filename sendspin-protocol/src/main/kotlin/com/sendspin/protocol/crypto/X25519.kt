package com.sendspin.protocol.crypto

import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement

/**
 * X25519, in the byte order that Noise uses.
 *
 * Two details make this easy to get wrong, so they are spelled out here.
 *
 * First, the byte order. Noise keeps both keys in the raw little-endian form
 * that RFC 7748 defines: the private key is the 32-byte scalar, and the
 * public key is the 32-byte u-coordinate. The Java API does not use that
 * order. `XECPublicKeySpec` takes the u-coordinate as a `BigInteger`, which
 * is big-endian, and `XECPublicKey.getEncoded()` returns an X.509 wrapper
 * rather than the key. The code below avoids both by keeping the raw bytes
 * and using the DER-wrapped key specs.
 *
 * Second, Android's Conscrypt provider rejects `XECPublicKeySpec` and
 * `XECPrivateKeySpec` outright: its key factory accepts only
 * `X509EncodedKeySpec` and `PKCS8EncodedKeySpec`. The DER wrappers below are
 * accepted by the JVM and by Android, and they carry the raw little-endian
 * bytes unchanged, so the same code gives the same bytes on both.
 */
public object X25519 {
    /** The size of both keys, in bytes. */
    public const val KEY_SIZE: Int = 32

    /**
     * The prefix of a PKCS#8 `PrivateKeyInfo` for an X25519 key.
     *
     * The object identifier 1.3.101.110 is the one that RFC 8410 gives for
     * X25519. The trailing bytes are the raw key.
     */
    private val PKCS8_PREFIX = byteArrayOf(
        0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e, 0x04, 0x22, 0x04, 0x20,
    )

    /** The prefix of an X.509 `SubjectPublicKeyInfo` for an X25519 key. */
    private val X509_PREFIX = byteArrayOf(
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e, 0x03, 0x21, 0x00,
    )

    /**
     * The base point, u = 9, in the raw little-endian form.
     *
     * Multiplying it with a private key gives that key's public key.
     */
    private val BASE_POINT = ByteArray(KEY_SIZE).also { it[0] = 9 }

    /**
     * Generates a private key.
     *
     * The bytes are clamped as RFC 7748 requires. Both the JVM and Android
     * clamp the scalar again when they use it, and clamping twice gives the
     * same result, so storing the clamped form is safe.
     */
    public fun generatePrivateKey(random: SecureRandom = SecureRandom()): ByteArray {
        val key = ByteArray(KEY_SIZE)
        random.nextBytes(key)
        key[0] = (key[0].toInt() and 248).toByte()
        key[KEY_SIZE - 1] = (key[KEY_SIZE - 1].toInt() and 127).toByte()
        key[KEY_SIZE - 1] = (key[KEY_SIZE - 1].toInt() or 64).toByte()
        return key
    }

    /** Derives the public key that belongs to [privateKey]. */
    public fun publicKey(privateKey: ByteArray): ByteArray =
        sharedSecret(privateKey, BASE_POINT)

    /** Computes the X25519 shared secret with [peerPublicKey]. */
    public fun sharedSecret(privateKey: ByteArray, peerPublicKey: ByteArray): ByteArray {
        require(privateKey.size == KEY_SIZE) { "A private key is 32 bytes" }
        require(peerPublicKey.size == KEY_SIZE) { "A public key is 32 bytes" }
        val keyFactory = java.security.KeyFactory.getInstance("XDH")
        val private = keyFactory.generatePrivate(
            PKCS8EncodedKeySpec(PKCS8_PREFIX + privateKey.copyOf()),
        )
        val public = keyFactory.generatePublic(
            X509EncodedKeySpec(X509_PREFIX + peerPublicKey.copyOf()),
        )
        val agreement = KeyAgreement.getInstance("XDH")
        agreement.init(private)
        agreement.doPhase(public, true)
        return agreement.generateSecret()
    }
}
