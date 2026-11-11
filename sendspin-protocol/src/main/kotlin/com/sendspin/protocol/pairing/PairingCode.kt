package com.sendspin.protocol.pairing

import com.sendspin.protocol.crypto.Hashing
import java.security.SecureRandom

/**
 * The dynamic pairing code and the commitment that binds it.
 *
 * The code comes from the handshake and from both sides' nonces, so neither
 * side alone chooses it. The client commits to its own nonce before it sees
 * the server's, and that is what stops a server steering the code to a value
 * it already knows.
 */
public object PairingCode {
    /** The size of a nonce, in bytes. */
    public const val NONCE_SIZE: Int = 32

    /** The number of digits in a dynamic pairing code. */
    public const val DIGITS: Int = 6

    /** The size of the binary code, in bytes. */
    public const val QR_CODE_SIZE: Int = 24

    /** The label that produces the commitment. */
    private const val COMMIT_LABEL = "sendspin-pair-commit-v1"

    /** The label that produces the code. */
    private const val DERIVE_LABEL = "sendspin-pairing-code-derive-v1"

    /** The number the digits are reduced by. */
    private const val CODE_RANGE: Long = 1_000_000L

    /** Draws a fresh nonce. */
    public fun generateNonce(random: SecureRandom = SecureRandom()): ByteArray =
        ByteArray(NONCE_SIZE).also { random.nextBytes(it) }

    /**
     * The commitment to a nonce.
     *
     * The client sends this in `client/pair-init`, before it has seen the
     * server's nonce.
     */
    public fun commit(nonce: ByteArray): ByteArray {
        require(nonce.size == NONCE_SIZE) { "a nonce is $NONCE_SIZE bytes, got ${nonce.size}" }
        return Hashing.sha256(COMMIT_LABEL.toByteArray(Charsets.UTF_8) + nonce)
    }

    /** The digest that both sides derive the code from. */
    public fun deriveDigest(
        handshakeHash: ByteArray,
        nonceA: ByteArray,
        nonceB: ByteArray,
    ): ByteArray {
        require(handshakeHash.size == 32) { "the handshake hash is 32 bytes" }
        require(nonceA.size == NONCE_SIZE) { "the server's nonce is $NONCE_SIZE bytes" }
        require(nonceB.size == NONCE_SIZE) { "our nonce is $NONCE_SIZE bytes" }
        return Hashing.sha256(
            DERIVE_LABEL.toByteArray(Charsets.UTF_8) + handshakeHash + nonceA + nonceB,
        )
    }

    /**
     * The six-digit code.
     *
     * The digest is read as an unsigned big-endian number and reduced by a
     * million. The reduction runs byte by byte, which is exact: the running
     * value stays under a million, so a byte times 256 cannot overflow.
     */
    public fun deriveDigits(
        handshakeHash: ByteArray,
        nonceA: ByteArray,
        nonceB: ByteArray,
    ): String {
        var remainder = 0L
        for (byte in deriveDigest(handshakeHash, nonceA, nonceB)) {
            remainder = (remainder * 256 + (byte.toInt() and 0xFF)) % CODE_RANGE
        }
        return remainder.toString().padStart(DIGITS, '0')
    }

    /**
     * The code as the operator reads it.
     *
     * The grouping is for the screen only. The separators never enter the
     * derivation, the entry, or the secret that CPace takes.
     */
    public fun group(code: String): String =
        if (code.length == DIGITS) code.substring(0, DIGITS / 2) + "-" + code.substring(DIGITS / 2) else code
}
