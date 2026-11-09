package com.sendspin.sender.net

import android.util.Log
import com.sendspin.protocol.crypto.NoiseSuite
import com.sendspin.protocol.psk.ClientKeyStore
import com.sendspin.protocol.session.AdmissionPolicy
import com.sendspin.protocol.session.Activity
import com.sendspin.protocol.session.SessionTransport
import com.sendspin.protocol.session.SourceSession
import com.sendspin.protocol.session.SourceSessionConfiguration
import com.sendspin.protocol.session.SourceSessionListener
import com.sendspin.protocol.session.SourceSessionState
import com.sendspin.sender.session.AndroidSessionClock
import com.sendspin.sender.session.SourceClient
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.nio.ByteBuffer

/**
 * The server that waits for the servers to connect.
 *
 * The client can either dial the server or wait for it to dial the client.
 * This class covers the second case. Every connection gets its own
 * session, and only one connection is admitted at a time.
 */
class InboundServer(
    private val keys: ClientKeyStore,
    private val suite: NoiseSuite,
    private val port: Int,
    private val listener: Listener,
) {
    /** What the server tells the rest of the app. */
    interface Listener {
        /** The state of the admitted connection changed. */
        fun onStateChanged(state: SourceSessionState)

        /** The server failed. */
        fun onError(message: String)
    }

    /**
     * One connection, with the session that runs on it.
     */
    private class Connection(
        val socket: WebSocket,
        val session: SourceSession,
    ) {
        /** The last state the session reported. */
        var state: SourceSessionState = SourceSessionState()
    }

    private val policy = AdmissionPolicy { keys.lastPlaybackServerId }
    private val sessions = mutableMapOf<WebSocket, Connection>()
    private var admitted: Connection? = null
    private var server: WebSocketServer? = null

    /** Starts the server. */
    fun start() {
        if (server != null) return
        val started = object : WebSocketServer(InetSocketAddress(port)) {
            override fun onOpen(socket: WebSocket, handshake: ClientHandshake) {
                onConnection(socket)
            }

            override fun onClose(socket: WebSocket, code: Int, reason: String, remote: Boolean) {
                onDisconnected(socket)
            }

            override fun onMessage(socket: WebSocket, message: String) {
                sessions[socket]?.session?.onTextMessage(message)
            }

            override fun onMessage(socket: WebSocket, bytes: ByteBuffer) {
                val data = ByteArray(bytes.remaining())
                bytes.get(data)
                sessions[socket]?.session?.onBinaryMessage(data)
            }

            override fun onError(socket: WebSocket, exception: Exception) {
                Log.w(TAG, "The connection failed", exception)
            }

            override fun onStart() = Unit
        }
        started.setReuseAddr(true)
        server = started
        started.start()
    }

    /** Stops the server, and closes every connection. */
    fun stop() {
        server?.stop()
        server = null
        sessions.values.forEach { it.session.sendGoodbye("shutdown") }
        sessions.clear()
        admitted = null
    }

    /** A server connected. */
    private fun onConnection(socket: WebSocket) {
        val session = SourceSession(
            keys = keys,
            transport = SocketTransport(socket),
            clock = AndroidSessionClock,
            listener = object : SourceSessionListener {
                override fun onStateChanged(state: SourceSessionState) {
                    onSessionState(socket, state)
                }

                override fun onClosed(reason: String) {
                    onDisconnected(socket)
                }
            },
            configuration = SourceSessionConfiguration(
                clientName = SourceClient.DEVICE_NAME,
                suite = suite,
            ),
        )
        sessions[socket] = Connection(socket, session)
        session.start()
    }

    /**
     * A session reported its state.
     *
     * The connection that the client keeps is the one with the highest
     * rank, so a second server has to earn its place.
     */
    private fun onSessionState(socket: WebSocket, state: SourceSessionState) {
        val incoming = sessions[socket] ?: return
        incoming.state = state
        val current = admitted
        if (current === incoming) {
            listener.onStateChanged(state)
            return
        }
        if (current == null) {
            admitted = incoming
            listener.onStateChanged(state)
            return
        }
        // The incoming connection has to beat the one the client holds.
        val takes = policy.admits(
            holder = AdmissionPolicy.Holder(
                serverId = current.state.serverId ?: "",
                activities = activitiesOf(current.state),
                pairingAttempt = current.state.pairingInProgress,
            ),
            incomingServerId = state.serverId ?: "",
            incomingActivities = activitiesOf(state),
            incomingPairingAttempt = state.pairingInProgress,
        )
        if (!takes) {
            // The incoming connection is turned away.
            incoming.session.sendGoodbye("concurrent_attempt")
            return
        }
        admitted = incoming
        current.session.sendGoodbye("another_server")
        listener.onStateChanged(state)
    }

    /** A connection closed. */
    private fun onDisconnected(socket: WebSocket) {
        val connection = sessions.remove(socket) ?: return
        if (admitted === connection) {
            admitted = null
            listener.onStateChanged(SourceSessionState())
        }
    }

    /** The activities a state declares. */
    private fun activitiesOf(state: SourceSessionState): Set<Activity> =
        state.activities.mapNotNull { Activity.fromWireName(it) }.toSet()

    /** The transport that writes to a socket. */
    private class SocketTransport(private val socket: WebSocket) : SessionTransport {
        override fun sendText(text: String) {
            socket.send(text)
        }

        override fun sendBinary(bytes: ByteArray) {
            socket.send(bytes)
        }

        override fun close() {
            socket.close()
        }
    }

    private companion object {
        const val TAG = "SendSpin"
    }
}
