package com.sendspin.sender.net

import android.util.Log
import com.sendspin.protocol.crypto.NoiseSuite
import com.sendspin.protocol.psk.ClientKeyStore
import com.sendspin.protocol.session.AdmissionPolicy
import com.sendspin.protocol.session.Activity
import com.sendspin.protocol.session.SessionTransport
import com.sendspin.protocol.session.SourceAudioFormat
import com.sendspin.protocol.session.SourceSession
import com.sendspin.protocol.session.SourceSessionConfiguration
import com.sendspin.protocol.session.SourceSessionListener
import com.sendspin.protocol.session.SourceSessionState
import com.sendspin.sender.capture.AudioCapture
import com.sendspin.sender.capture.CaptureStatusHolder
import com.sendspin.sender.session.AndroidSessionClock
import com.sendspin.sender.session.CapturePipeline
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The server that waits for the servers to connect.
 *
 * The phone waits for the server to dial it, and the server finds the phone
 * over the advertisement on the local network. Every connection gets its own
 * session, and only one connection is admitted at a time.
 */
class InboundServer(
    private val keys: ClientKeyStore,
    private val suite: NoiseSuite,
    private val port: Int,
    private val format: SourceAudioFormat,
    @Volatile
    private var capture: AudioCapture,
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
    private val sessions = ConcurrentHashMap<WebSocket, Connection>()
    @Volatile private var admitted: Connection? = null
    @Volatile private var server: WebSocketServer? = null

    /** Whether [stop] has started, so the bind callback does nothing. */
    @Volatile private var stopping = false

    /** The thread that polls the clock of the admitted session. */
    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "sendspin-inbound").apply { isDaemon = true }
        }

    /** The task that polls the clock of the admitted session. */
    private var pollTask: ScheduledFuture<*>? = null

    /** Turns the captured blocks into the chunks the protocol carries. */
    private val pipeline = CapturePipeline(
        format = format,
        onChunk = { pcm, timestamp -> admitted?.session?.onCapturedAudio(pcm, timestamp) },
        onCaptureError = { message ->
            CaptureStatusHolder.publishError(message)
            listener.onError(message)
        },
        onMeasurements = { blocks, peak ->
            CaptureStatusHolder.publishMeasurements(blocks, peak)
        },
    )

    /**
     * Starts the server and the capture.
     *
     * @param onStarted runs once the socket accepts connections, so the
     *   caller can advertise the port only when it is listening
     */
    fun start(onStarted: () -> Unit = {}) {
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

            override fun onError(socket: WebSocket?, exception: Exception) {
                if (socket == null) {
                    // The bind failed, so no server can connect.
                    Log.e(TAG, "The server could not listen on the port $port", exception)
                    listener.onError("The server could not listen on the port $port")
                } else {
                    Log.w(TAG, "The connection failed", exception)
                }
            }

            override fun onStart() {
                // A stop may have run before the bind finished.
                if (stopping || server == null) return
                Log.i(TAG, "The server listens on the port $port")
                // The capture and the clock polling start only now, because
                // a bind that failed would leave both running for nothing.
                capture.start(pipeline)
                // The clock filter needs a regular measurement, so the
                // admitted session is polled on a timer.
                pollTask = scheduler.scheduleWithFixedDelay(
                    { admitted?.session?.pollClock() },
                    POLL_INTERVAL_MILLIS,
                    POLL_INTERVAL_MILLIS,
                    TimeUnit.MILLISECONDS,
                )
                onStarted()
            }
        }
        started.setReuseAddr(true)
        server = started
        started.start()
    }

    /** Stops the server, the capture, and every connection. */
    /**
     * Swaps the capture that feeds the pipeline.
     *
     * The pairing runs on a silent capture, and the consent swaps in the
     * real one. The swap does not touch the connections, so a session
     * that Music Assistant already dialed stays up, and the audio it
     * asked for carries through it as soon as the stream is requested.
     *
     * @param newCapture the capture that takes over
     */
    fun swapCapture(newCapture: AudioCapture) {
        newCapture.start(pipeline)
        capture.stop()
        capture = newCapture
    }

    fun stop() {
        stopping = true
        pollTask?.cancel(true)
        pollTask = null
        scheduler.shutdownNow()
        capture.stop()
        server?.stop()
        server = null
        sessions.values.forEach { it.session.sendGoodbye("shutdown") }
        sessions.clear()
        admitted = null
        pipeline.reset()
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
                    Log.i(TAG, "The session closed: $reason")
                    onDisconnected(socket)
                }
            },
            configuration = SourceSessionConfiguration(
                clientName = DEVICE_NAME,
                suite = suite,
                format = format,
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
            pipeline.reset()
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
        pipeline.reset()
        current.session.sendGoodbye("another_server")
        listener.onStateChanged(state)
    }

    /** A connection closed. */
    private fun onDisconnected(socket: WebSocket) {
        val connection = sessions.remove(socket) ?: return
        if (admitted === connection) {
            admitted = null
            pipeline.reset()
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

    companion object {
        private const val TAG = "SendSpin"

        /** How often the admitted session is polled for a clock exchange. */
        private const val POLL_INTERVAL_MILLIS = 100L

        /** The name the client shows to the server. */
        const val DEVICE_NAME = "SendSpin"

        /** The audio format the client announces. */
        val FORMAT = SourceAudioFormat(codec = "pcm", sampleRate = 48000, channels = 2, bitDepth = 16)
    }
}
