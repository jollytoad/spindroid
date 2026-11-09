package com.sendspin.protocol.noise

import com.sendspin.protocol.crypto.NoiseSuite
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The AEAD state that Noise calls a `CipherState`.
 *
 * It holds a key and a nonce counter. Each message uses the current counter
 * value and then increments it.
 */
internal class CipherState(
    private val suite: NoiseSuite,
    private val key: ByteArray?,
) {
    private var nonce: Long = 0
    private var cipher: Cipher? = null

    /** The key algorithm name that the platform accepted. */
    private var keyAlgorithm: String? = null

    fun encrypt(associatedData: ByteArray, plaintext: ByteArray): ByteArray {
        val key = key ?: return plaintext
        check(nonce < MAX_NONCE) { "The Noise nonce is exhausted" }
        val result = prepare(Cipher.ENCRYPT_MODE, associatedData, key).doFinal(plaintext)
        nonce++
        return result
    }

    fun decrypt(associatedData: ByteArray, ciphertext: ByteArray): ByteArray {
        val key = key ?: return ciphertext
        check(nonce < MAX_NONCE) { "The Noise nonce is exhausted" }
        val result = prepare(Cipher.DECRYPT_MODE, associatedData, key).doFinal(ciphertext)
        nonce++
        return result
    }

    /**
     * A copy with the same key and the same counter value.
     *
     * The counter is part of the state, so a copy must carry it over.
     */
    fun copy(): CipherState {
        val copy = CipherState(suite, key?.copyOf())
        copy.nonce = nonce
        copy.keyAlgorithm = keyAlgorithm
        return copy
    }

    /** A cipher ready to seal or open one message. */
    private fun prepare(mode: Int, associatedData: ByteArray, key: ByteArray): Cipher {
        val cipher = cipher ?: newCipher().also { this.cipher = it }
        cipher.init(mode, keySpec(mode, key), parameterSpec())
        cipher.updateAAD(associatedData)
        return cipher
    }

    private fun keySpec(mode: Int, key: ByteArray): SecretKeySpec {
        keyAlgorithm?.let { return SecretKeySpec(key, it) }

        // The key algorithm name differs between the platforms. The JDK
        // expects "ChaCha20-Poly1305", while Conscrypt expects the base name
        // "ChaCha20". Try each one on a fresh cipher, so the probe does not
        // count as a message.
        for (candidate in suite.keyAlgorithmNames()) {
            val probe = newCipher()
            try {
                probe.init(mode, SecretKeySpec(key, candidate), parameterSpec())
                keyAlgorithm = candidate
                return SecretKeySpec(key, candidate)
            } catch (_: Exception) {
                // Try the next name.
            }
        }
        throw IllegalStateException("No key algorithm is accepted for ${suite.protocolName}")
    }

    private fun parameterSpec() = when (suite) {
        NoiseSuite.AES_GCM_SHA256 -> GCMParameterSpec(TAG_LENGTH_BITS, suite.nonce(nonce))
        NoiseSuite.CHACHA_POLY_SHA256 -> IvParameterSpec(suite.nonce(nonce))
    }

    private fun newCipher(): Cipher {
        var failure: Exception? = null
        for (name in suite.cipherNames()) {
            try {
                return Cipher.getInstance(name)
            } catch (exception: Exception) {
                failure = exception
            }
        }
        throw IllegalStateException(
            "No cipher is available for ${suite.protocolName}",
            failure,
        )
    }

    private companion object {
        /** The value 2^64-1 is reserved, so the last usable counter is one below it. */
        const val MAX_NONCE = Long.MAX_VALUE
        const val TAG_LENGTH_BITS = 128
    }
}
