package app.gubbins.sendspin.crypto

/**
 * base64url without padding.
 *
 * SendSpin encodes Curve25519 public keys, PSK identifiers and Noise
 * handshake messages this way. The decoder is strict: it rejects padding,
 * characters outside the alphabet, and any encoding that is not the canonical
 * one for its bytes. Two different strings must never decode to the same
 * bytes, because the pairing token and the PSK identifier are compared as
 * text.
 */
public object Base64Url {
    private const val ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    public fun encode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size * 4 + 2) / 3)
        var i = 0
        while (i + 3 <= bytes.size) {
            val chunk = ((bytes[i].toInt() and 0xFF) shl 16) or
                ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                (bytes[i + 2].toInt() and 0xFF)
            out.append(ALPHABET[(chunk ushr 18) and 0x3F])
            out.append(ALPHABET[(chunk ushr 12) and 0x3F])
            out.append(ALPHABET[(chunk ushr 6) and 0x3F])
            out.append(ALPHABET[chunk and 0x3F])
            i += 3
        }
        when (bytes.size - i) {
            1 -> {
                val chunk = (bytes[i].toInt() and 0xFF) shl 16
                out.append(ALPHABET[(chunk ushr 18) and 0x3F])
                out.append(ALPHABET[(chunk ushr 12) and 0x3F])
            }

            2 -> {
                val chunk = ((bytes[i].toInt() and 0xFF) shl 16) or
                    ((bytes[i + 1].toInt() and 0xFF) shl 8)
                out.append(ALPHABET[(chunk ushr 18) and 0x3F])
                out.append(ALPHABET[(chunk ushr 12) and 0x3F])
                out.append(ALPHABET[(chunk ushr 6) and 0x3F])
            }
        }
        return out.toString()
    }

    /** Returns null when [text] is not a canonical base64url string without padding. */
    public fun decode(text: String): ByteArray? {
        if (text.length % 4 == 1) return null
        val out = ByteArray(text.length * 3 / 4)
        var outIndex = 0
        var buffer = 0
        var bits = 0
        for (symbol in text) {
            val value = ALPHABET.indexOf(symbol)
            if (value < 0) return null
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[outIndex++] = ((buffer ushr bits) and 0xFF).toByte()
                // Keep only the bits that have not been turned into a byte yet.
                buffer = buffer and ((1 shl bits) - 1)
            }
        }
        // Trailing bits must be zero, otherwise the text is not the canonical
        // encoding of these bytes.
        if (bits > 0 && buffer != 0) return null
        if (outIndex != out.size) return null
        return out
    }
}
