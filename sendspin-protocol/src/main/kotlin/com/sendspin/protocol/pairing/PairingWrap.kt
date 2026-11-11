package com.sendspin.protocol.pairing

import com.sendspin.protocol.crypto.Aead
import com.sendspin.protocol.crypto.Hashing
import com.sendspin.protocol.crypto.NoiseSuite
import java.nio.ByteBuffer

/**
 * The sealing of the two values that cross the wire only under the CPace
 * output: the new long-term key, and the opening of the client's nonce.
 *
 * The two labels give two different keys, so a value sealed under one can
 * never be opened under the other.
 */
public object PairingWrap {
    /** The label of the key that seals the long-term key. */
    public val PSK_LABEL: ByteArray = "sendspin-pair-psk-wrap-v1".toByteArray(Charsets.UTF_8)

    /** The label of the key that seals the nonce opening. */
    public val NONCE_LABEL: ByteArray = "sendspin-pair-nonce-wrap-v1".toByteArray(Charsets.UTF_8)

    /**
     * The 12-byte all-zero nonce.
     *
     * This is safe only because each wrap key is derived per field and seals
     * exactly one value, exactly once. A zero nonce reused under one key
     * would be a disaster, so this constant must not travel to any other
     * use of the AEAD.
     */
    private val ZERO_NONCE = ByteArray(Aead.NONCE_SIZE)

    /**
     * The session id that binds a CPace run to the handshake, the attempt and
     * the round.
     */
    public fun sessionId(handshakeHash: ByteArray, pairingIndex: Int, round: Int): ByteArray {
        require(handshakeHash.size == 32) { "the handshake hash is 32 bytes" }
        return "sendspin-pair-pake-v1".toByteArray(Charsets.UTF_8) +
            handshakeHash +
            ByteBuffer.allocate(4).putInt(pairingIndex).array() +
            ByteBuffer.allocate(4).putInt(round).array()
    }

    /** The key that seals a value. */
    public fun wrapKey(label: ByteArray, sid: ByteArray, isk: ByteArray): ByteArray =
        Hashing.sha256(label + sid + isk)

    /**
     * Seals a 32-byte value.
     *
     * The result is 48 bytes: the ciphertext and the tag.
     */
    public fun seal(suite: NoiseSuite, key: ByteArray, plaintext: ByteArray): ByteArray =
        Aead.seal(suite, key, ZERO_NONCE, ByteArray(0), plaintext)
}
