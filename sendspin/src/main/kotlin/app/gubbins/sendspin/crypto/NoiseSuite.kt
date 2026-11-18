package app.gubbins.sendspin.crypto

/**
 * The two cipher suites that SendSpin defines.
 *
 * The name follows the Noise convention, `<DH>_<cipher>_<hash>`. The full
 * protocol name is `Noise_KKpsk2_` followed by this.
 */
public enum class NoiseSuite(
    /** The suite name, as it appears in the protocol name and in `client/init`. */
    public val protocolName: String,
) {
    CHACHA_POLY_SHA256("25519_ChaChaPoly_SHA256"),
    AES_GCM_SHA256("25519_AESGCM_SHA256"),
    ;

    /**
     * The names the cipher may have.
     *
     * The JDK calls this cipher `ChaCha20-Poly1305`. Android's Conscrypt
     * registers `ChaCha20/Poly1305/NoPadding` and points the plain name at
     * it. The key algorithm is a separate matter: the JDK expects
     * `ChaCha20-Poly1305`, while Conscrypt expects the base name
     * `ChaCha20`. Both are tried, and the one that works is remembered.
     */
    internal fun cipherNames(): List<String> = when (this) {
        CHACHA_POLY_SHA256 -> listOf(
            "ChaCha20-Poly1305",
            "ChaCha20/Poly1305/NoPadding",
            "ChaCha20-Poly1305/None/NoPadding",
        )

        AES_GCM_SHA256 -> listOf("AES/GCM/NoPadding")
    }

    internal fun keyAlgorithmNames(): List<String> = when (this) {
        CHACHA_POLY_SHA256 -> listOf("ChaCha20-Poly1305", "ChaCha20")
        AES_GCM_SHA256 -> listOf("AES")
    }

    /**
     * Builds the 12-byte AEAD nonce for a message counter.
     *
     * The layout differs per suite, and it is not a detail to guess at. The
     * Noise specification defines it in sections 12.3 and 12.4:
     *
     * - ChaCha20-Poly1305 takes four zero bytes, then the counter in
     *   little-endian order.
     * - AES-GCM takes four zero bytes, then the counter in big-endian order.
     */
    internal fun nonce(counter: Long): ByteArray {
        val nonce = ByteArray(12)
        for (index in 0 until 8) {
            val shift = when (this) {
                CHACHA_POLY_SHA256 -> index * 8
                AES_GCM_SHA256 -> (7 - index) * 8
            }
            nonce[4 + index] = ((counter ushr shift) and 0xFF).toByte()
        }
        return nonce
    }

    public companion object {
        /**
         * Finds the suite with the given wire name.
         *
         * @param name the name as it appears in `client/init`
         * @return the suite, or null when the name is not one of the two
         */
        public fun fromWireName(name: String): NoiseSuite? =
            entries.firstOrNull { it.protocolName == name }
    }
}
