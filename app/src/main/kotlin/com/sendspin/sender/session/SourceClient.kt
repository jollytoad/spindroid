package com.sendspin.sender.session

import android.util.Log
import com.sendspin.protocol.codec.PcmResampler
import com.sendspin.protocol.crypto.NoiseSuite
import com.sendspin.protocol.psk.ClientKeyStore
import com.sendspin.protocol.session.SessionPhase
import com.sendspin.protocol.session.SourceAudioFormat
import com.sendspin.protocol.session.SourceSession
import com.sendspin.protocol.session.SourceSessionConfiguration
import com.sendspin.protocol.session.SourceSessionListener
import com.sendspin.protocol.session.SourceSessionState
import com.sendspin.sender.capture.AudioCapture
import com.sendspin.sender.capture.CaptureListener
import com.sendspin.sender.capture.PcmBlock
import com.sendspin.sender.net.WebSocketTransport
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
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

    @Volatile
    private var running = false

    @Volatile
    private var reconnectAttempt = 0

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
        scheduler.shutdownNow()
        session?.sendGoodbye("shutdown")
        transport?.release()
        transport = null
        session = null
    }

    /** Opens the connection to the server. */
    private fun connect() {
        if (!running) return
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
                suite = suite,
                format = FORMAT,
            ),
        )
        session = source
        socket.open()
        source.start()
        // The clock filter needs a regular measurement, so the session is
        // polled on a timer.
        scheduler.scheduleWithFixedDelay(
            { source.pollClock() },
            POLL_INTERVAL_MILLIS,
            POLL_INTERVAL_MILLIS,
            TimeUnit.MILLISECONDS,
        )
    }

    /** Handles a connection that dropped. */
    private fun onConnectionLost(reason: String) {
        if (!running) return
        Log.i(TAG, "The connection closed: $reason")
        state = state.copy(
            phase = SessionPhase.CLOSED,
            streaming = false,
            serverRequestedAudio = false,
        )
        listener.onStateChanged(state)
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
        reconnectAttempt += 1
        val delay = minOf(
            BASE_RECONNECT_DELAY_MILLIS * reconnectAttempt,
            MAX_RECONNECT_DELAY_MILLIS,
        )
        Log.i(TAG, "Reconnecting in ${delay}ms")
        scheduler.schedule({ connect() }, delay, TimeUnit.MILLISECONDS)
    }

    /** The session reports what it did. */
    private val sessionListener = object : SourceSessionListener {
        override fun onStateChanged(state: SourceSessionState) {
            this@SourceClient.state = state
            listener.onStateChanged(state)
        }

        override fun onClosed(reason: String) {
            onConnectionLost(reason)
        }
    }

    /** The capture hands the audio over. */
    private val captureListener = object : CaptureListener {
        override fun onBlock(block: PcmBlock) {
            val converted = PcmResampler.convert(
                samples = block.samples,
                channels = block.channels,
                sourceRate = block.sampleRate,
                targetRate = FORMAT.sampleRate,
                targetChannels = FORMAT.channels,
            )
            session?.onCapturedAudio(toBytes(converted), block.captureTimestampMicros)
        }

        override fun onError(message: String) {
            listener.onError(message)
        }
    }

    /** Starts the capture, once the client runs. */
    fun startCapture() {
        capture.start(captureListener)
    }

    /** Stops the capture. */
    fun stopCapture() {
        capture.stop()
    }

    /** Turns the samples into the little-endian bytes the protocol carries. */
    private fun toBytes(samples: ShortArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        var index = 0
        for (sample in samples) {
            out[index] = (sample.toInt() and 0xFF).toByte()
            out[index + 1] = (sample.toInt() shr 8 and 0xFF).toByte()
            index += 2
        }
        return out
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
        private const val BASE_RECONNECT_DELAY_MILLIS = 1000L
        private const val MAX_RECONNECT_DELAY_MILLIS = 30000L
    }
}
