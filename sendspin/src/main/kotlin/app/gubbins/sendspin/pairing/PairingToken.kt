package app.gubbins.sendspin.pairing

import app.gubbins.sendspin.crypto.Base32
import app.gubbins.sendspin.crypto.Base64Url

/**
 * The pairing token that transfers a pairing secret from a client to a
 * server.
 *
 * The token is the ASCII string `SP:`, a version character, and a body. The
 * body is the payload in base32, with the `=` padding removed and every `2`
 * written as `9`, so the whole token stays inside the set of characters a QR
 * code can carry.
 */
public object PairingToken {
    /** The prefix that marks a token. */
    public const val PREFIX: String = "SP:"

    /** The version that carries a client key and a pairing PSK. */
    public const val VERSION_PAIRING_PSK: Char = '0'

    /** The version that carries a dynamic pairing code. */
    public const val VERSION_DYNAMIC_CODE: Char = '1'

    /**
     * Encodes a version 0 token.
     *
     * @param clientKey the client's static public key, 32 bytes
     * @param pairingPsk the pairing PSK, 32 bytes
     */
    public fun encodePairingPsk(clientKey: ByteArray, pairingPsk: ByteArray): String {
        require(clientKey.size == 32) { "The client key is 32 bytes" }
        require(pairingPsk.size == 32) { "The pairing PSK is 32 bytes" }
        return PREFIX + VERSION_PAIRING_PSK + encodeBody(clientKey + pairingPsk)
    }

    /**
     * Encodes a version 1 token, which carries a dynamic pairing code.
     *
     * @param code the pairing code, 24 bytes
     */
    public fun encodeDynamicCode(code: ByteArray): String {
        require(code.size == 24) { "The dynamic pairing code is 24 bytes" }
        return PREFIX + VERSION_DYNAMIC_CODE + encodeBody(code)
    }

    /**
     * Decodes a token, whatever a person typed into the server.
     *
     * The input is trimmed, upper-cased, and the `SP:` prefix is optional.
     * A version this code does not know is rejected.
     */
    public fun decode(input: String): Token? {
        var text = input.trim().uppercase()
        if (text.startsWith(PREFIX)) {
            text = text.substring(PREFIX.length)
        }
        if (text.isEmpty()) return null
        val version = text[0]
        val body = text.substring(1)
        return when (version) {
            VERSION_PAIRING_PSK -> {
                val payload = decodeBody(body) ?: return null
                if (payload.size < PAIRING_PSK_PAYLOAD_SIZE) return null
                Token.PairingPsk(
                    clientKey = payload.copyOfRange(0, 32),
                    pairingPsk = payload.copyOfRange(32, 64),
                )
            }

            VERSION_DYNAMIC_CODE -> {
                val payload = decodeBody(body) ?: return null
                if (payload.size < DYNAMIC_CODE_SIZE) return null
                Token.DynamicCode(payload.copyOfRange(0, DYNAMIC_CODE_SIZE))
            }

            else -> null
        }
    }

    /** The base32 body of a token, with the padding removed and `2` written as `9`. */
    private fun encodeBody(payload: ByteArray): String =
        Base32.encode(payload).replace('2', '9')

    /** The inverse of [encodeBody]. */
    private fun decodeBody(body: String): ByteArray? =
        Base32.decode(body.replace('9', '2'))

    private const val PAIRING_PSK_PAYLOAD_SIZE = 64
    private const val DYNAMIC_CODE_SIZE = 24

    /** A decoded pairing token. */
    public sealed interface Token {
        /** A version 0 token, which carries the client key and the pairing PSK. */
        public data class PairingPsk(
            val clientKey: ByteArray,
            val pairingPsk: ByteArray,
        ) : Token {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is PairingPsk) return false
                return clientKey.contentEquals(other.clientKey) &&
                    pairingPsk.contentEquals(other.pairingPsk)
            }

            override fun hashCode(): Int =
                31 * clientKey.contentHashCode() + pairingPsk.contentHashCode()
        }

        /** A version 1 token, which carries a dynamic pairing code. */
        public data class DynamicCode(val code: ByteArray) : Token {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is DynamicCode) return false
                return code.contentEquals(other.code)
            }

            override fun hashCode(): Int = code.contentHashCode()
        }
    }
}
