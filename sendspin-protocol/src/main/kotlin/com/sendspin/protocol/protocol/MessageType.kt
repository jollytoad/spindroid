package com.sendspin.protocol.protocol

/**
 * The names of the message types.
 *
 * The names are listed here so that a typo in one of them fails the build
 * rather than the connection.
 */
public object MessageType {
    /** The client's first message. It names the client's static key and the suite. */
    public const val CLIENT_INIT: String = "client/init"

    /** The server's reply to [CLIENT_INIT]. It names the server's static key. */
    public const val SERVER_INIT: String = "server/init"

    /** One of the two Noise handshake messages. */
    public const val NOISE_HANDSHAKE: String = "noise/handshake"

    /** The server's refusal to talk. It arrives instead of [SERVER_INIT]. */
    public const val SERVER_ERROR: String = "server/error"

    /** The server's greeting. */
    public const val SERVER_HELLO: String = "server/hello"

    /** The client's greeting. */
    public const val CLIENT_HELLO: String = "client/hello"

    /** The server's declaration of what the connection is for. */
    public const val SERVER_ACTIVATE: String = "server/activate"

    /** A clock synchronisation request. */
    public const val CLIENT_TIME: String = "client/time"

    /** The reply to a clock synchronisation request. */
    public const val SERVER_TIME: String = "server/time"

    /** A state update from the client. */
    public const val CLIENT_STATE: String = "client/state"

    /** A state update from the server. */
    public const val SERVER_STATE: String = "server/state"

    /** A command from the server. */
    public const val SERVER_COMMAND: String = "server/command"

    /** A command from the client. */
    public const val CLIENT_COMMAND: String = "client/command"

    /** The client's farewell. */
    public const val CLIENT_GOODBYE: String = "client/goodbye"

    /** The client leaving its group. */
    public const val CLIENT_LEAVE: String = "client/leave"

    /** The start of a stream from the client to the server. */
    public const val CLIENT_STREAM_START: String = "client-stream/start"

    /** The end of a stream from the client to the server. */
    public const val CLIENT_STREAM_END: String = "client-stream/end"

    /** The start of a stream from the server to the client. */
    public const val STREAM_START: String = "stream/start"

    /** The end of a stream from the server to the client. */
    public const val STREAM_END: String = "stream/end"

    /** The clearing of a stream's buffer. */
    public const val STREAM_CLEAR: String = "stream/clear"

    /** A group update from the server. */
    public const val GROUP_UPDATE: String = "group/update"

    /** The server dropping a pairing. */
    public const val SERVER_UNPAIR: String = "server/unpair"

    /** The client starting a pairing attempt. */
    public const val CLIENT_PAIR_INIT: String = "client/pair-init"

    /** The client reporting that it is waiting before it pairs. */
    public const val CLIENT_PAIR_PENDING: String = "client/pair-pending"

    /** The client delivering a new pre-shared key. */
    public const val CLIENT_PAIR_FINALIZE: String = "client/pair-finalize"

    /** The server confirming that it stored a pre-shared key. */
    public const val SERVER_PAIR_FINALIZE: String = "server/pair-finalize"

    /** The server starting a pairing round. */
    public const val SERVER_PAIR_INIT: String = "server/pair-init"

    /** The server sending its public share. */
    public const val SERVER_PAIR_AUTH: String = "server/pair-auth"

    /** The client sending its public share. */
    public const val CLIENT_PAIR_AUTH: String = "client/pair-auth"

    /** The server sending its key confirmation. */
    public const val SERVER_PAIR_CONFIRM: String = "server/pair-confirm"

    /** The client sending its key confirmation. */
    public const val CLIENT_PAIR_CONFIRM: String = "client/pair-confirm"

    /** The client asking for another round. */
    public const val CLIENT_PAIR_RETRY: String = "client/pair-retry"

    /** The end of a pairing attempt, from either side. */
    public const val PAIR_ABORT: String = "pair/abort"
}
