package app.gubbins.sendspin.psk

import app.gubbins.sendspin.crypto.Base64Url
import app.gubbins.sendspin.crypto.Hashing

/**
 * The pre-shared keys that the handshake uses.
 *
 * The specification defines three categories. A long-term key comes from a
 * pairing. A pairing key comes from the pairing token. The Sentinel key is
 * a published constant, and it stands in when no other key applies.
 */
public object SendspinPsk {
    /** The label that precedes the key material when the identifier is computed. */
    private const val ID_LABEL = "sendspin-psk-id-v1"

    /** The label that produces the Sentinel key. */
    private const val SENTINEL_LABEL = "sendspin-sentinel-psk-v1"

    /**
     * The Sentinel key.
     *
     * It is a published constant, so it provides no authentication. It only
     * marks a session as unpaired.
     */
    public val SENTINEL: ByteArray by lazy {
        Hashing.sha256(SENTINEL_LABEL.toByteArray(Charsets.UTF_8))
    }

    /** The identifier of the Sentinel key. */
    public val SENTINEL_ID: String by lazy { id(SENTINEL) }

    /**
     * The identifier of a key.
     *
     * The identifier is `base64url(SHA-256("sendspin-psk-id-v1" || psk))`, and
     * it is 43 characters long.
     */
    public fun id(psk: ByteArray): String = Base64Url.encode(
        Hashing.sha256(ID_LABEL.toByteArray(Charsets.UTF_8) + psk),
    )
}

/** The category of a pre-shared key. */
public enum class PskCategory(
    /** The value that appears in the `psk_category` field of the handshake. */
    public val wireName: String,
) {
    /** A long-term key, from a pairing record. */
    LONG_TERM("lt"),

    /** A pairing key, from the pairing token. */
    PAIRING("pr"),

    /** The Sentinel key. */
    SENTINEL("sn"),
    ;

    public companion object {
        /** Finds a category by its wire name, or null. */
        public fun fromWireName(name: String): PskCategory? =
            entries.firstOrNull { it.wireName == name }
    }
}
