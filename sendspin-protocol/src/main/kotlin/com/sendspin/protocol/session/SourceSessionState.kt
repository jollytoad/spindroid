package com.sendspin.protocol.session

/**
 * The phase a source session is in.
 *
 * The phases follow the order of the messages. A session moves forward
 * through them, and back to [CLOSED] when the connection ends.
 */
public enum class SessionPhase {
    /** Nothing has started. */
    IDLE,

    /** The cleartext handshake runs. */
    HANDSHAKING,

    /** The server sent its hello, and the client sends its own. */
    HELLO,

    /** The session is active, and the clock is not in sync yet. */
    SYNCING,

    /** The clock is in sync, and the session waits for the server to ask for audio. */
    READY,

    /** The server asked for audio, and the session streams it. */
    STREAMING,

    /** The connection ended. */
    CLOSED,
}

/**
 * A snapshot of everything a client shows about its session.
 *
 * The session publishes a new snapshot whenever something changes, so the
 * user interface never has to ask the session a question.
 */
public data class SourceSessionState(
    /** The phase the session is in. */
    public val phase: SessionPhase = SessionPhase.IDLE,
    /** The name the server gave in its hello. */
    public val serverName: String? = null,
    /** The identifier of the server, which is its static public key. */
    public val serverId: String? = null,
    /** The session is paired, so the server may use the captured audio. */
    public val paired: Boolean = false,
    /** A pairing attempt is running. */
    public val pairingInProgress: Boolean = false,
    /** The purposes the server declared. */
    public val activities: List<String> = emptyList(),
    /** The roles the server activated. */
    public val activeRoles: List<String> = emptyList(),
    /** The client offers its audio to the server. */
    public val available: Boolean = false,
    /** The clock is in sync with the server. */
    public val clockConverged: Boolean = false,
    /** The clock error, in microseconds. */
    public val clockErrorMicros: Long? = null,
    /** The server asked for audio, and the client has not sent it yet. */
    public val serverRequestedAudio: Boolean = false,
    /** The client is sending audio to the server. */
    public val streaming: Boolean = false,
    /**
     * The names of the servers this client is paired with.
     *
     * The list comes from the pairing records the client keeps, so it
     * survives a reconnect.
     */
    public val pairedServers: List<String> = emptyList(),
    /** The last error, when the session failed. */
    public val error: String? = null,
)

/**
 * The callbacks a session makes.
 *
 * Every callback has a default, so an implementation only overrides the
 * ones it needs.
 */
public interface SourceSessionListener {
    /** The session state changed. */
    public fun onStateChanged(state: SourceSessionState) {}

    /** The connection ended. */
    public fun onClosed(reason: String) {}

    /** The server asked for audio, and the client opened the stream. */
    public fun onStreamOpened(format: SourceAudioFormat) {}

    /** The client stopped sending audio. */
    public fun onStreamClosed() {}
}
