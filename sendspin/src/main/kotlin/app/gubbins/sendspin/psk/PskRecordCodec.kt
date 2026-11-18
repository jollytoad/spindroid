package app.gubbins.sendspin.psk

import app.gubbins.sendspin.crypto.Base64Url

/**
 * The text format that stores pairing records.
 *
 * A record is one line, and its fields are separated by `|`. The server name
 * is base64url-encoded before it is written, so a name that contains a space,
 * the separator, or a line break round-trips unchanged.
 *
 * A version 1 line starts with `1|` and carries four fields. An earlier build
 * wrote three fields separated by spaces, which breaks on a name such as
 * `Music Assistant (studio)`. The decoder still reads that older format, so a
 * record that an earlier build wrote is not lost.
 */
public object PskRecordCodec {
    /** The version of the line format this codec writes. */
    private const val VERSION = "1"

    /** The separator between the fields of a record. */
    private const val FIELD_SEPARATOR = "|"

    /** The separator between records. */
    private const val RECORD_SEPARATOR = "\n"

    /** The separator that the older format used. */
    private const val LEGACY_FIELD_SEPARATOR = " "

    /** The size of a long-term key, in bytes. */
    private const val PSK_SIZE = 32

    /**
     * Encodes the records, one per line.
     *
     * @param records the records to write, most recently used first
     */
    public fun encode(records: List<PskRecord>): String =
        records.joinToString(RECORD_SEPARATOR) { record ->
            listOf(
                VERSION,
                record.serverId,
                Base64Url.encode(record.serverName.toByteArray(Charsets.UTF_8)),
                Base64Url.encode(record.psk),
            ).joinToString(FIELD_SEPARATOR)
        }

    /**
     * Decodes the records, and skips a line that is malformed.
     *
     * @param text the text that [encode] wrote, or the older format
     */
    public fun decode(text: String): List<PskRecord> {
        val out = mutableListOf<PskRecord>()
        for (line in text.split(RECORD_SEPARATOR)) {
            if (line.isEmpty()) continue
            val record = decodeVersioned(line) ?: decodeLegacy(line) ?: continue
            out += record
        }
        return out
    }

    /** Decodes a line that [encode] wrote, or returns null. */
    private fun decodeVersioned(line: String): PskRecord? {
        val parts = line.split(FIELD_SEPARATOR)
        if (parts.size != 4) return null
        if (parts[0] != VERSION) return null
        val name = decodeText(parts[2]) ?: return null
        val psk = decodePsk(parts[3]) ?: return null
        return PskRecord(
            serverId = parts[1],
            psk = psk,
            serverName = name,
            category = PskCategory.LONG_TERM,
        )
    }

    /** Decodes a line in the older format, or returns null. */
    private fun decodeLegacy(line: String): PskRecord? {
        val parts = line.split(LEGACY_FIELD_SEPARATOR)
        if (parts.size != 3) return null
        val psk = decodePsk(parts[2]) ?: return null
        return PskRecord(
            serverId = parts[0],
            psk = psk,
            serverName = parts[1],
            category = PskCategory.LONG_TERM,
        )
    }

    /** Decodes a base64url text field, or returns null. */
    private fun decodeText(text: String): String? =
        Base64Url.decode(text)?.let { String(it, Charsets.UTF_8) }

    /** Decodes a key, or returns null when the field is not a 32-byte key. */
    private fun decodePsk(text: String): ByteArray? {
        val psk = Base64Url.decode(text) ?: return null
        return if (psk.size == PSK_SIZE) psk else null
    }
}
