package app.gubbins.sendspin.crypto

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A one-shot AEAD seal.
 *
 * The Noise layer keeps a counter and seals many messages with one cipher
 * object, so it does its own thing. The pairing code seals two values, each
 * under a key of its own and each exactly once, so it needs a plain call
 * with a given nonce.
 */
public object Aead {
    /** The size of the nonce, in bytes. */
    public const val NONCE_SIZE: Int = 12

    /**
     * Seals [plaintext].
     *
     * @param suite the cipher suite that the connection negotiated
     * @param key the key
     * @param nonce the 12-byte nonce
     * @param associatedData the associated data, which the tag covers
     * @param plaintext the bytes to seal
     * @return the ciphertext, with the tag at the end
     */
    public fun seal(
        suite: NoiseSuite,
        key: ByteArray,
        nonce: ByteArray,
        associatedData: ByteArray,
        plaintext: ByteArray,
    ): ByteArray {
        require(nonce.size == NONCE_SIZE) { "the nonce is $NONCE_SIZE bytes, got ${nonce.size}" }
        val cipher = prepare(suite, key, nonce)
        cipher.updateAAD(associatedData)
        return cipher.doFinal(plaintext)
    }

    /**
     * Opens a sealed value.
     *
     * @param suite the cipher suite that the connection negotiated
     * @param key the key
     * @param nonce the 12-byte nonce
     * @param associatedData the associated data, which the tag covers
     * @param ciphertext the sealed bytes, with the tag at the end
     * @return the plaintext
     * @throws javax.crypto.AEADBadTagException when the tag does not match
     */
    public fun open(
        suite: NoiseSuite,
        key: ByteArray,
        nonce: ByteArray,
        associatedData: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray {
        require(nonce.size == NONCE_SIZE) { "the nonce is $NONCE_SIZE bytes, got ${nonce.size}" }
        val cipher = prepare(suite, key, nonce, Cipher.DECRYPT_MODE)
        cipher.updateAAD(associatedData)
        return cipher.doFinal(ciphertext)
    }

    /**
     * A cipher ready to seal or open.
     *
     * The two platforms name the cipher and the key algorithm differently,
     * so every combination is tried until one is accepted.
     */
    private fun prepare(
        suite: NoiseSuite,
        key: ByteArray,
        nonce: ByteArray,
        mode: Int = Cipher.ENCRYPT_MODE,
    ): Cipher {
        var failure: Exception? = null
        for (cipherName in suite.cipherNames()) {
            for (keyName in suite.keyAlgorithmNames()) {
                try {
                    val cipher = Cipher.getInstance(cipherName)
                    cipher.init(mode, SecretKeySpec(key, keyName), parameterSpec(suite, nonce))
                    return cipher
                } catch (exception: Exception) {
                    failure = exception
                }
            }
        }
        throw IllegalStateException(
            "No cipher is available for ${suite.protocolName}",
            failure,
        )
    }

    private fun parameterSpec(suite: NoiseSuite, nonce: ByteArray) = when (suite) {
        NoiseSuite.AES_GCM_SHA256 -> GCMParameterSpec(TAG_LENGTH_BITS, nonce)
        NoiseSuite.CHACHA_POLY_SHA256 -> IvParameterSpec(nonce)
    }

    /** The size of the authentication tag, in bits. */
    private const val TAG_LENGTH_BITS = 128
}
