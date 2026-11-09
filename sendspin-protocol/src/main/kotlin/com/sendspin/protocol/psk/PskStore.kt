package com.sendspin.protocol.psk

/**
 * A stored pre-shared key.
 *
 * The key belongs to one server. The server identifier is the server's static
 * public key, so a record cannot be used with a different server.
 */
public data class PskRecord(
    /** The server's static public key, as base64url. */
    val serverId: String,
    /** The pre-shared key material. */
    val psk: ByteArray,
    /** The server's friendly name, for display. */
    val serverName: String,
    /** The category of the key. */
    val category: PskCategory,
) {
    /** The identifier that the server names in the first handshake message. */
    val pskId: String get() = SendspinPsk.id(psk)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PskRecord) return false
        return serverId == other.serverId &&
            psk.contentEquals(other.psk) &&
            serverName == other.serverName &&
            category == other.category
    }

    override fun hashCode(): Int {
        var result = serverId.hashCode()
        result = 31 * result + psk.contentHashCode()
        result = 31 * result + serverName.hashCode()
        result = 31 * result + category.hashCode()
        return result
    }
}

/**
 * The store of pre-shared keys.
 *
 * The implementation lives outside this module, because the keys are secret
 * and the Android app keeps them in encrypted storage. This interface keeps
 * the protocol code free of any dependency on that.
 */
public interface PskStore {
    /**
     * Finds the record with the given identifier.
     *
     * A record whose category differs from the one the server named is not a
     * match, because the server expects a specific category.
     */
    public fun find(pskId: String, category: PskCategory): PskRecord?

    /**
     * Stores a record, replacing any record for the same server.
     *
     * The store holds at least five records. When it is full, adding a
     * record evicts the least recently used one. A record that backs an open
     * connection is never evicted.
     */
    public fun put(record: PskRecord)

    /** Removes the record for a server. */
    public fun remove(serverId: String)

    /** Marks a record as backing an open connection, so it cannot be evicted. */
    public fun markInUse(serverId: String)

    /** Marks a record as no longer backing an open connection. */
    public fun markUnused(serverId: String)
}

/**
 * The choice of key for a handshake.
 *
 * The server names a key in the first handshake message. The client looks it
 * up. A miss in the first handshake falls back to the Sentinel key, and the
 * session stays unpaired. A miss in a re-handshake fails, because the
 * session was paired and the server expects it to stay paired.
 */
public sealed interface PskSelection {
    /** A key was found. */
    public data class Found(val record: PskRecord) : PskSelection

    /** No key was found, and the Sentinel key stands in. */
    public data class SentinelFallback(val requestedId: String) : PskSelection

    /** No key was found, and the handshake must fail. */
    public data class Failure(val requestedId: String) : PskSelection

    /** The key material to mix into the handshake. */
    public val psk: ByteArray
        get() = when (this) {
            is Found -> record.psk
            is SentinelFallback -> SendspinPsk.SENTINEL
            is Failure -> SendspinPsk.SENTINEL
        }
}

/**
 * Chooses the key for a handshake.
 *
 * @param store where the keys are looked up
 */
public class PskSelector(private val store: PskStore) {
    /**
     * Chooses the key for a handshake.
     *
     * @param pskId the identifier the server named
     * @param category the category the server named
     * @param isRehandshake whether this is a re-handshake
     */
    public fun select(
        pskId: String,
        category: PskCategory,
        isRehandshake: Boolean,
    ): PskSelection {
        val record = store.find(pskId, category)
        if (record != null) return PskSelection.Found(record)
        // A miss during a re-handshake fails: the session was paired.
        if (isRehandshake) return PskSelection.Failure(pskId)
        return PskSelection.SentinelFallback(pskId)
    }
}
