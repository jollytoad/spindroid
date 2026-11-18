package app.gubbins.sendspin.noise

/**
 * The transport phase of a Noise handshake.
 *
 * Every message after the handshake is sealed with one of the two cipher
 * states and no associated data.
 */
public class NoiseTransport internal constructor(
    private val send: CipherState,
    private val receive: CipherState,
    /**
     * The handshake hash. A re-handshake uses it as its prologue, which binds
     * the new session to the one it replaces.
     */
    public val handshakeHash: ByteArray,
) {
    /** Seals a message for sending. */
    public fun encrypt(plaintext: ByteArray): ByteArray {
        require(plaintext.size <= MAX_PLAINTEXT_SIZE) {
            "A Noise message carries at most $MAX_PLAINTEXT_SIZE bytes of plaintext"
        }
        return send.encrypt(EMPTY_ASSOCIATED_DATA, plaintext)
    }

    /** Opens a received message. */
    public fun decrypt(ciphertext: ByteArray): ByteArray {
        require(ciphertext.size <= MAX_MESSAGE_SIZE) {
            "A Noise message is at most $MAX_MESSAGE_SIZE bytes"
        }
        return receive.decrypt(EMPTY_ASSOCIATED_DATA, ciphertext)
    }

    public companion object {
        /** The largest Noise message, including the AEAD tag. */
        public const val MAX_MESSAGE_SIZE: Int = 65535

        /** The largest plaintext that fits in a Noise message. */
        public const val MAX_PLAINTEXT_SIZE: Int = MAX_MESSAGE_SIZE - 16

        private val EMPTY_ASSOCIATED_DATA = ByteArray(0)
    }
}
