package com.sendspin.sender.session

import android.util.Log
import com.sendspin.protocol.crypto.NoiseSuite
import com.sendspin.protocol.psk.ClientKeyStore
import com.sendspin.protocol.session.ReconnectPolicy
import com.sendspin.protocol.session.SessionPhase
import com.sendspin.protocol.session.SourceAudioFormat
import com.sendspin.protocol.session.SourceSession
import com.sendspin.protocol.session.SourceSessionConfiguration
import com.sendspin.protocol.session.SourceSessionListener
import com.sendspin.protocol.session.SourceSessionState
import com.sendspin.sender.capture.AudioCapture
import com.sendspin.sender.capture.CaptureStatusHolder
import com.sendspin.sender.net.WebSocketTransport
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The source client, from the phone's point of view.
 *
 * It owns the connection to the server, the session that runs the
 * protocol, and the capture that feeds the audio. It reconnects on its own
 * when the connection drops, and it keeps the state that the user
 * interface shows.
 */
class SourceClient(
    private val keys: ClientKeyStore,
    private val serverAddress: String,
    private val suite: NoiseSuite,
    private val capture: AudioCapture,
    private val listener: Listener,
) {
    /**
     * What the client tells the rest of the app.
     */
    interface Listener {
        /** The state of the client changed. */
        fun onStateChanged(state: SourceSessionState)

        /** The client failed. */
        fun onError(message: String)
    }

    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "sendspin-client").apply { isDaemon = true }
        }

    private var transport: WebSocketTransport? = null
    private var session: SourceSession? = null
    private var state: SourceSessionState = SourceSessionState()

    /** The task that polls the clock of the running session. */
    private var pollTask: ScheduledFuture<*>? = null

    @Volatile
    private var running = false

    /**
     * Whether a reconnect is already on its way.
     *
     * A socket that fails reports both a failure and a close, and the
     * session reports the end of the session as well. Without this guard,
     * one drop would open several connections.
     */
    @Volatile
    private var reconnectPending = false

    /** The state of the client. */
    val currentState: SourceSessionState get() = state

    /** Starts the client, and keeps it running until it is stopped. */
    fun start() {
        if (running) return
        running = true
        connect()
    }

    /** Stops the client, and closes the connection. */
    fun stop() {
        if (!running) return
        running = false
        reconnectPending = false
        scheduler.shutdownNow()
        pollTask?.cancel(true)
        pollTask = null
        session?.sendGoodbye("shutdown")
        transport?.release()
        transport = null
        session = null
    }

    /** Opens the connection to the server. */
    private fun connect() {
        if (!running) return
        reconnectPending = false
        // The old session and its socket go away, so a reconnect does not
        // leave them behind.
        pollTask?.cancel(true)
        pollTask = null
        transport?.release()
        val url = "ws://$serverAddress:$PORT/sendspin"
        val socket = WebSocketTransport(
            url = url,
            onText = { text -> session?.onTextMessage(text) },
            onBinary = { bytes -> session?.onBinaryMessage(bytes) },
            onClosed = { reason -> onConnectionLost(reason) },
            onFailure = { error -> onConnectionLost(error.message ?: "The connection failed") },
        )
        transport = socket
        val source = SourceSession(
            keys = keys,
            transport = socket,
            clock = AndroidSessionClock,
            listener = sessionListener,
            configuration = SourceSessionConfiguration(
                clientName = DEVICE_NAME,
                suite = currentSuite,
                format = FORMAT,
            ),
        )
        session = source
        socket.open()
        source.start()
        // The clock filter needs a regular measurement, so the session is
        // polled on a timer.
        pollTask = scheduler.scheduleWithFixedDelay(
            { source.pollClock() },
            POLL_INTERVAL_MILLIS,
            POLL_INTERVAL_MILLIS,
            TimeUnit.MILLISECONDS,
        )
    }

    /**
     * The suite the client offers.
     *
     * The server may not take the suite the client picked. When it refuses
     * the connection, the client offers the other one. That happens once,
     * because a server that refuses both suites does not want the client at
     * all.
     */
    private var currentSuite: NoiseSuite = suite

    /** Whether the client already offered the other suite. */
    private var triedOtherSuite = false

    /** Handles a connection that dropped. */
    private fun onConnectionLost(reason: String) {
        if (!running) return
        if (reconnectPending) return
        Log.i(TAG, "The connection closed: $reason")
        state = state.copy(
            phase = SessionPhase.CLOSED,
            streaming = false,
            serverRequestedAudio = false,
        )
        listener.onStateChanged(state)
        if (reason == SourceSession.SERVER_REFUSED) {
            // The server did not take the suite the client offered. The
            // other one is tried at once, because the server is there and
            // the answer will not change.
            if (!triedOtherSuite) {
                triedOtherSuite = true
                currentSuite = if (currentSuite == NoiseSuite.AES_GCM_SHA256) {
                    NoiseSuite.CHACHA_POLY_SHA256
                } else {
                    NoiseSuite.AES_GCM_SHA256
                }
                Log.i(TAG, "The server refused the suite, trying ${currentSuite.protocolName}")
                reconnectPending = true
                scheduler.schedule({ connect() }, 0, TimeUnit.MILLISECONDS)
                return
            }
        }
        scheduleReconnect()
    }

    /**
     * Schedules the next attempt to connect.
     *
     * The wait grows with every attempt, so a server that is down does
     * not keep the phone busy. It never grows past the ceiling.
     */
    private fun scheduleReconnect() {
        if (!running) return
        reconnectPending = true
        val delay = reconnect.nextDelayMillis()
        Log.i(TAG, "Reconnecting in ${delay}ms")
        scheduler.schedule({ connect() }, delay, TimeUnit.MILLISECONDS)
    }

    /** The wait between two attempts to connect. */
    private val reconnect = ReconnectPolicy()

    /** The session reports what it did. */
    private val sessionListener = object : SourceSessionListener {
        override fun onStateChanged(state: SourceSessionState) {
            this@SourceClient.state = state
            // A session that reaches the ready state has a working
            // connection, so the next drop starts the wait over.
            if (state.phase == SessionPhase.READY || state.phase == SessionPhase.STREAMING) {
                reconnect.reset()
            }
            listener.onStateChanged(state)
        }

        override fun onClosed(reason: String) {
            onConnectionLost(reason)
        }
    }

    /** The capture hands the audio over. */
    private val pipeline = CapturePipeline(
        format = FORMAT,
        onChunk = { pcm, timestamp -> session?.onCapturedAudio(pcm, timestamp) },
        onCaptureError = { message ->
            CaptureStatusHolder.publishError(message)
            listener.onError(message)
        },
        onMeasurements = { blocks, peak ->
            CaptureStatusHolder.publishMeasurements(blocks, peak)
        },
    )

    /** Starts the capture, once the client runs. */
    fun startCapture() {
        capture.start(pipeline)
    }

    /** Stops the capture. */
    fun stopCapture() {
        capture.stop()
    }

    companion object {
        private const val TAG = "SendSpin"

        /** The port the SendSpin server listens on. */
        const val PORT = 8927

        /** The name the client shows to the server. */
        const val DEVICE_NAME = "SendSpin"

        /** The audio format the client announces. */
        val FORMAT = SourceAudioFormat(codec = "pcm", sampleRate = 48000, channels = 2, bitDepth = 16)

        private const val POLL_INTERVAL_MILLIS = 100L
    }
}
