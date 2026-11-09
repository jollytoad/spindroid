package com.sendspin.protocol.psk

/**
 * The outcome of matching the key that the server named.
 */
public sealed interface ClientPskResolution {
    /** The client holds the key. */
    public data class Matched(
        /** The key material. */
        val psk: ByteArray,
        /** The category the key is held under. */
        val category: PskCategory,
        /** The long-term record, when the key is a long-term one. */
        val record: PskRecord? = null,
    ) : ClientPskResolution {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Matched) return false
            return psk.contentEquals(other.psk) &&
                category == other.category &&
                record == other.record
        }

        override fun hashCode(): Int {
            var result = psk.contentHashCode()
            result = 31 * result + category.hashCode()
            result = 31 * result + (record?.hashCode() ?: 0)
            return result
        }
    }

    /**
     * The client holds no key with that identifier, so the Sentinel key
     * stands in. The session continues unpaired.
     */
    public data class SentinelFallback(
        /** The identifier the server named. */
        val requestedId: String,
    ) : ClientPskResolution

    /** The handshake must fail. */
    public data class Failure(
        /** Why the handshake fails. */
        val reason: String,
    ) : ClientPskResolution
}

/**
 * Matches the key that the server named in the first handshake message.
 *
 * The server names an identifier and a category. The client compares the
 * identifier against the keys it holds under that category. A miss in the
 * first handshake falls back to the Sentinel key, and a miss in a
 * re-handshake fails, because a re-handshake only happens on a session that
 * was already paired.
 *
 * A record that belongs to another server is a misbinding rather than a
 * miss, so it never falls back to the Sentinel key. It fails the handshake.
 */
public class ClientPskResolver(
    private val keys: ClientKeyStore,
) {
    /**
     * Matches the key the server named.
     *
     * @param pskId the identifier the server named
     * @param category the category the server named
     * @param serverId the identifier of the server, which a long-term record
     *   must match
     * @param isRehandshake whether this handshake replaces a running session
     */
    public fun resolve(
        pskId: String,
        category: PskCategory,
        serverId: String?,
        isRehandshake: Boolean,
    ): ClientPskResolution {
        when (category) {
            PskCategory.SENTINEL -> {
                if (pskId == SendspinPsk.SENTINEL_ID) {
                    return ClientPskResolution.Matched(
                        SendspinPsk.SENTINEL,
                        PskCategory.SENTINEL,
                    )
                }
            }

            PskCategory.PAIRING -> {
                val pairing = keys.pairingPsk
                if (pskId == SendspinPsk.id(pairing)) {
                    return ClientPskResolution.Matched(pairing, PskCategory.PAIRING)
                }
            }

            PskCategory.LONG_TERM -> {
                val record = keys.findLongTerm(pskId)
                if (record != null) {
                    // The record belongs to one server. A record that names
                    // another server is a misbinding, not a miss, so it never
                    // falls back to the Sentinel key.
                    if (serverId != null && record.serverId != serverId) {
                        return ClientPskResolution.Failure(
                            "The key belongs to another server",
                        )
                    }
                    return ClientPskResolution.Matched(
                        record.psk,
                        PskCategory.LONG_TERM,
                        record,
                    )
                }
            }
        }
        if (isRehandshake) {
            return ClientPskResolution.Failure(
                "The server named a key this client does not hold",
            )
        }
        return ClientPskResolution.SentinelFallback(pskId)
    }
}
