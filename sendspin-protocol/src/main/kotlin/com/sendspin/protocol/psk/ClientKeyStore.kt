package com.sendspin.protocol.psk

/**
 * The keys a client keeps for its handshakes.
 *
 * The client holds three kinds of keys:
 *
 * - its static key pair, which identifies it,
 * - the pairing PSK, which pairs it with any server, and
 * - the long-term PSKs, one per server it has paired with.
 *
 * The Android app keeps these in encrypted storage, so the interface lives
 * here and the implementation stays outside the protocol module.
 */
public interface ClientKeyStore {
    /** The client's static private key. */
    public val staticPrivateKey: ByteArray

    /** The pairing PSK of this client. It never changes. */
    public val pairingPsk: ByteArray

    /** The long-term records, most recently used first. */
    public fun longTermRecords(): List<PskRecord>

    /**
     * The long-term record with the given identifier.
     *
     * @return the record, or null when the client holds none
     */
    public fun findLongTerm(pskId: String): PskRecord?

    /** Stores a long-term record, replacing the record of the same server. */
    public fun putLongTerm(record: PskRecord)

    /** Removes the long-term record of a server. */
    public fun removeLongTerm(serverId: String)

    /**
     * The server that most recently held the connection while the playback
     * activity was among its activities.
     *
     * The specification uses this server to break a tie between two idle
     * servers.
     */
    public var lastPlaybackServerId: String?
}
