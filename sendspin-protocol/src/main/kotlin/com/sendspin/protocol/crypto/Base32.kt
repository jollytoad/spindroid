package com.sendspin.protocol.crypto

/**
 * base32 per RFC 4648, without padding, using the standard alphabet.
 *
 * The pairing token uses this encoding with one change: every `2` is written
 * as `9`, so the token stays inside the QR code alphanumeric set. That
 * transliteration belongs to the token, not here.
 */
public object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    /** Encodes [bytes] without the `=` padding. */
    public fun encode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size * 8 + 4) / 5)
        var buffer = 0
        var bits = 0
        for (byte in bytes) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                out.append(ALPHABET[(buffer ushr bits) and 0x1F])
            }
            buffer = buffer and ((1 shl bits) - 1)
        }
        if (bits > 0) {
            out.append(ALPHABET[(buffer shl (5 - bits)) and 0x1F])
        }
        return out.toString()
    }

    /**
     * Decodes [text], with or without `=` padding.
     *
     * Returns null when the text holds a character outside the alphabet, has
     * an impossible length, or carries trailing bits that are not zero. The
     * last check matters: without it, several different strings would decode
     * to the same bytes.
     */
    public fun decode(text: String): ByteArray? {
        var body = text
        var padding = 0
        while (body.endsWith("=")) {
            body = body.dropLast(1)
            padding++
        }
        // A padded base32 body is a whole number of 8-character groups, and
        // the padding can only be 1, 3, 4 or 6 characters.
        if (padding > 0) {
            if ((body.length + padding) % 8 != 0) return null
            if (padding !in intArrayOf(1, 3, 4, 6)) return null
        }
        if (body.length % 8 == 1) return null

        val out = ByteArray(body.length * 5 / 8)
        var outIndex = 0
        var buffer = 0
        var bits = 0
        for (symbol in body) {
            val value = ALPHABET.indexOf(symbol)
            if (value < 0) return null
            buffer = (buffer shl 5) or value
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out[outIndex++] = ((buffer ushr bits) and 0xFF).toByte()
                buffer = buffer and ((1 shl bits) - 1)
            }
        }
        if (outIndex != out.size) return null
        // Any bits left over must be zero padding bits.
        if (bits > 0 && buffer != 0) return null
        return out
    }
}
