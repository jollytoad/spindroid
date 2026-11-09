package com.sendspin.protocol.noise

import com.sendspin.protocol.crypto.Hashing
import com.sendspin.protocol.crypto.NoiseSuite

/**
 * The symmetric state that Noise mixes together during a handshake.
 *
 * It holds a chaining key, a running hash, and the cipher state. The hash
 * doubles as the associated data for every message, which binds each message
 * to everything that came before it.
 */
internal class SymmetricState(
    private val suite: NoiseSuite,
    protocolName: String,
) {
    private var chainingKey: ByteArray
    private var hash: ByteArray
    private var cipher: CipherState

    /** The handshake hash, used to bind a re-handshake to the session it follows. */
    val handshakeHash: ByteArray
        get() = hash

    init {
        val nameBytes = protocolName.toByteArray(Charsets.UTF_8)
        hash = if (nameBytes.size <= HASH_LENGTH) {
            nameBytes + ByteArray(HASH_LENGTH - nameBytes.size)
        } else {
            Hashing.sha256(nameBytes)
        }
        chainingKey = hash
        cipher = CipherState(suite, null)
    }

    /** The Noise HKDF, which is not the HKDF in RFC 5869. */
    private fun hkdf(inputKeyMaterial: ByteArray, outputs: Int): List<ByteArray> =
        Hashing.hkdf(chainingKey, inputKeyMaterial, outputs)

    fun mixKey(inputKeyMaterial: ByteArray) {
        val output = hkdf(inputKeyMaterial, 2)
        chainingKey = output[0]
        cipher = CipherState(suite, output[1])
    }

    fun mixHash(data: ByteArray) {
        hash = Hashing.sha256(hash + data)
    }

    fun mixKeyAndHash(inputKeyMaterial: ByteArray) {
        val output = hkdf(inputKeyMaterial, 3)
        chainingKey = output[0]
        mixHash(output[1])
        cipher = CipherState(suite, output[2])
    }

    fun encryptAndHash(plaintext: ByteArray): ByteArray {
        val ciphertext = cipher.encrypt(hash, plaintext)
        mixHash(ciphertext)
        return ciphertext
    }

    fun decryptAndHash(ciphertext: ByteArray): ByteArray {
        val plaintext = cipher.decrypt(hash, ciphertext)
        mixHash(ciphertext)
        return plaintext
    }

    /** Produces the two transport cipher states. */
    fun split(): Pair<CipherState, CipherState> {
        val output = hkdf(ByteArray(0), 2)
        return CipherState(suite, output[0]) to CipherState(suite, output[1])
    }

    /**
     * A copy of this state.
     *
     * The initiator needs this to try a second PSK after the first one
     * failed. Reading a message changes the state, so the retry must start
     * from the state as it was before the first attempt.
     */
    fun copy(): SymmetricState {
        val copy = SymmetricState(suite, "")
        copy.chainingKey = chainingKey.copyOf()
        copy.hash = hash.copyOf()
        copy.cipher = cipher.copy()
        return copy
    }

    private companion object {
        const val HASH_LENGTH = 32
    }
}
