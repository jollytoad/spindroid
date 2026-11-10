package com.sendspin.protocol.session

/**
 * The transport that carries a session's messages.
 *
 * The protocol module does not know about WebSockets, so the application
 * supplies this. The implementation on Android uses Java-WebSocket.
 *
 * The cleartext handshake uses text frames. Everything after it uses binary
 * frames, because the Noise transport layer already frames the messages.
 */
public interface SessionTransport {
    /** Sends a cleartext JSON message. Only the handshake uses this. */
    public fun sendText(text: String)

    /** Sends a binary frame. Every message after the handshake uses this. */
    public fun sendBinary(bytes: ByteArray)

    /** Closes the transport. */
    public fun close()
}
