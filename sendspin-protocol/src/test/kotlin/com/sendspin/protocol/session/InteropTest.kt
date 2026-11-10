package com.sendspin.protocol.session

import com.sendspin.protocol.crypto.Base64Url
import com.sendspin.protocol.crypto.NoiseSuite
import com.sendspin.protocol.psk.ClientKeyStore
import com.sendspin.protocol.psk.PskRecord
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The source session, driven against a server that was written separately.
 *
 * The server in `tools/test_server.py` is an independent implementation of
 * the protocol. It was written from the specification rather than from the
 * Kotlin code, so a mistake in one implementation does not hide a mistake in
 * the other. This test proves that the two speak the same language.
 *
 * The test skips itself when the machine has no Python, or no
 * `cryptography` package for it.
 */
class InteropTest {
    /** The epoch both clocks start from, so the offset between them stays small. */
    private val epoch = 1_700_000_000_000L

    /** A key store that holds the key the server names. */
    private class TestKeyStore(
        override val staticPrivateKey: ByteArray = ByteArray(32) { (it + 7).toByte() },
        override val pairingPsk: ByteArray,
    ) : ClientKeyStore {
        val records = linkedMapOf<String, PskRecord>()

        override fun longTermRecords(): List<PskRecord> = records.values.toList()

        override fun findLongTerm(pskId: String): PskRecord? =
            records.values.firstOrNull { it.pskId == pskId }

        override fun putLongTerm(record: PskRecord) {
            records[record.serverId] = record
        }

        override fun removeLongTerm(serverId: String) {
            records.remove(serverId)
        }

        override var lastPlaybackServerId: String? = null
    }

    /** A clock that runs from a chosen epoch. */
    private class RealClock(private val epoch: Long) : SessionClock {
        private val start = System.nanoTime()

        override fun nowMicros(): Long = epoch + (System.nanoTime() - start) / 1000
    }

    /**
     * A transport that queues what arrives.
     *
     * The session is driven from the test thread, so the messages are
     * handled in the order they arrived, and the clock polling never
     * overlaps with a message.
     */
    private class QueuedTransport : SessionTransport {
        private val queue = LinkedBlockingQueue<Any>()

        /** The socket that carries the connection. */
        lateinit var socket: WebSocketClient

        override fun sendText(text: String) {
            socket.send(text)
        }

        override fun sendBinary(bytes: ByteArray) {
            socket.send(bytes)
        }

        override fun close() {
            socket.close()
        }

        /** Adds a text message to the queue. */
        fun offer(text: String) {
            queue.put(text)
        }

        /** Adds a binary message to the queue. */
        fun offer(bytes: ByteArray) {
            queue.put(bytes)
        }

        /**
         * Takes the next message, waiting for it.
         *
         * @return the message, or null when the wait ran out
         */
        fun take(millis: Long): Any? = queue.poll(millis, TimeUnit.MILLISECONDS)
    }

    /** The state the session publishes, and the errors it reports. */
    private class TestListener : SourceSessionListener {
        var closedReason: String? = null

        override fun onStateChanged(state: SourceSessionState) = Unit

        override fun onClosed(reason: String) {
            closedReason = reason
        }

        override fun onStreamOpened(format: SourceAudioFormat) = Unit

        override fun onStreamClosed() = Unit
    }

    /** A socket that hands what arrives to the transport. */
    private class TestSocket(
        uri: URI,
        private val transport: QueuedTransport,
    ) : WebSocketClient(uri) {
        override fun onOpen(handshake: ServerHandshake) = Unit

        override fun onMessage(message: String) {
            transport.offer(message)
        }

        override fun onMessage(bytes: ByteBuffer) {
            val data = ByteArray(bytes.remaining())
            bytes.get(data)
            transport.offer(data)
        }

        override fun onClose(code: Int, reason: String, remote: Boolean) = Unit

        override fun onError(exception: Exception) = Unit
    }

    /** What one session achieved. */
    private class SessionRun(
        /** The state the session ended in. */
        val state: SourceSessionState,
        /** The state the session was in when the server asked for the audio. */
        val readyState: SourceSessionState,
        /** The reason the session closed, when it closed. */
        val closedReason: String?,
        /** What the server recorded while the session ran. */
        val entries: List<Map<String, String>>,
        /** The clock the session used. */
        val clock: RealClock,
        /** The socket the session ran on. */
        val socket: TestSocket,
    ) {
        /** Ends the session, the way a network drop does. */
        fun close() {
            socket.close()
        }
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    fun `the session talks to an independently written server`() {
        val server = startTestServer()
        try {
            val run = server.runSession()
            assertNull(run.closedReason, "The session closed early")
            assertTrue(run.state.clockConverged, "The clock did not converge")
            assertTrue(run.state.available, "The session did not report itself available")
            assertEquals(SessionPhase.STREAMING, run.readyState.phase)
            assertTrue(
                run.readyState.streaming,
                "The server did not ask for the audio",
            )
            assertTrue(run.state.streaming, "The stream did not open")
            assertEquals(SessionPhase.STREAMING, run.state.phase)

            // The server recorded what it saw.
            assertTrue(
                run.entries.any { it["event"] == "handshake-complete" },
                "The server did not finish the handshake",
            )
            assertTrue(
                run.entries.any { it["event"] == "client-state" && it["available"] == "true" },
                "The server never saw the client report itself available",
            )
            assertTrue(
                run.entries.any { it["event"] == "stream-start" },
                "The server never saw the stream start",
            )
            val audio = run.entries.filter { it["event"] == "audio" }
            assertTrue(audio.isNotEmpty(), "The server never received any audio")
            val bytes = audio.sumOf { it["bytes"]?.toInt() ?: 0 }
            assertTrue(bytes >= CHUNK_BYTES, "The server received less audio than the client sent")

            // The timestamps the client sent are on the server's clock. The
            // offset between the two clocks is what the filter measured, so
            // the timestamp must be the client's clock plus that offset.
            val offset = run.entries.firstOrNull { it["event"] == "time-exchange" }
                ?.get("difference")?.toLong() ?: 0L
            val timestamp = audio.first()["timestamp"]?.toLong() ?: 0L
            val expected = run.clock.nowMicros() + offset
            assertTrue(
                kotlin.math.abs(timestamp - expected) < 1_000_000,
                "The timestamp $timestamp is not the clock ${run.clock.nowMicros()} plus $offset",
            )
            assertNull(run.state.error, "The session reported an error")
        } finally {
            server.stop()
        }
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    fun `the session comes back after the connection drops`() {
        val server = startTestServer()
        try {
            // The first session runs and ends.
            val first = server.runSession()
            assertTrue(first.state.streaming, "The first stream did not open")
            first.close()
            Thread.sleep(300)

            // The second session runs on a new connection. The client holds
            // the same keys, so it pairs again the same way.
            val second = server.runSession()
            assertNull(second.closedReason, "The second session closed early")
            assertTrue(second.state.clockConverged, "The clock did not converge again")
            assertTrue(second.state.available, "The session did not report itself available")
            assertTrue(second.state.streaming, "The second stream did not open")
            assertTrue(
                second.entries.any { it["event"] == "handshake-complete" },
                "The server did not finish the second handshake",
            )
            assertTrue(
                second.entries.any { it["event"] == "audio" },
                "The server received no audio on the second connection",
            )
        } finally {
            server.stop()
        }
    }

    /**
     * The test server, and the session that talks to it.
     */
    private inner class TestServer(
        private val process: Process,
        private val port: Int,
        private val pairingPsk: ByteArray,
        private val report: File,
    ) {
        /**
         * Runs one session from the start to the end.
         *
         * The session opens the socket, handshakes, syncs the clock, waits
         * for the server to ask for the audio, and then sends a few chunks.
         */
        fun runSession(): SessionRun {
            val transport = QueuedTransport()
            val socket = TestSocket(URI("ws://127.0.0.1:$port/sendspin"), transport)
            transport.socket = socket
            assertTrue(socket.connectBlocking(10, TimeUnit.SECONDS), "The socket did not open")

            val listener = TestListener()
            val clock = RealClock(epoch)
            val session = SourceSession(
                keys = TestKeyStore(pairingPsk = pairingPsk),
                transport = transport,
                clock = clock,
                listener = listener,
                configuration = SourceSessionConfiguration(
                    clientName = "Interop Phone",
                    suite = NoiseSuite.CHACHA_POLY_SHA256,
                ),
            )

            val before = readReport(report).size
            session.start()

            // The handshake, the hello, the activation and the clock
            // synchronisation all happen in this loop.
            val deadline = System.currentTimeMillis() + 60_000
            while (System.currentTimeMillis() < deadline) {
                drain(transport, session)
                session.pollClock()
                if (session.state.available && session.state.streaming) break
                Thread.sleep(10)
            }

            // The first block of audio opens the stream.
            val readyState = session.state
            val pcm = ByteArray(CHUNK_BYTES) { (it % 256).toByte() }
            session.onCapturedAudio(pcm, clock.nowMicros())

            // A few more blocks, so the server sees a stream of audio.
            repeat(5) {
                Thread.sleep(20)
                session.onCapturedAudio(pcm, clock.nowMicros())
            }
            Thread.sleep(500)

            val entries = readReport(report).drop(before)
            return SessionRun(
                state = session.state,
                readyState = readyState,
                closedReason = listener.closedReason,
                entries = entries,
                clock = clock,
                socket = socket,
            )
        }

        /** Stops the server. */
        fun stop() {
            process.destroy()
        }
    }

    /** Starts the server, and waits for it to listen. */
    private fun startTestServer(): TestServer {
        val script = findScript()
        assumeTrue(script != null, "The test server script was not found")
        assumeTrue(pythonWorks(script!!), "The test server cannot run on this machine")

        val report = File(reportPath())
        report.parentFile?.mkdirs()
        report.delete()

        val pairingPsk = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val port = freePort()
        val process = startServer(script, port, pairingPsk, report)
        assertTrue(waitForPort(port, process), "The server did not start")
        return TestServer(process, port, pairingPsk, report)
    }

    /** Feeds the queued messages to the session. */
    private fun drain(transport: QueuedTransport, session: SourceSession) {
        while (true) {
            val message = transport.take(1) ?: return
            when (message) {
                is String -> session.onTextMessage(message)
                is ByteArray -> session.onBinaryMessage(message)
            }
        }
    }

    /** The path of the server script. */
    private fun findScript(): File? {
        val fromEnvironment = System.getenv("SENDSPIN_TEST_SERVER")
        if (fromEnvironment != null) {
            val file = File(fromEnvironment)
            if (file.isFile) return file
        }
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            val candidate = File(directory, "tools/test_server.py")
            if (candidate.isFile) return candidate
            directory = directory.parentFile
        }
        return null
    }

    /** Whether the machine can run the server. */
    private fun pythonWorks(script: File): Boolean {
        return try {
            val process = ProcessBuilder("python3", "-c", "import cryptography")
                .redirectErrorStream(true)
                .start()
            process.inputStream.readBytes()
            process.waitFor(20, TimeUnit.SECONDS) && process.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }

    /** Starts the server. */
    private fun startServer(
        script: File,
        port: Int,
        pairingPsk: ByteArray,
        report: File,
    ): Process {
        // The arguments use the `name=value` form. A base64url value may
        // start with a dash, and the separate form would read that as a
        // flag.
        val process = ProcessBuilder(
            "python3",
            script.absolutePath,
            "--port=$port",
            "--pairing-psk=${Base64Url.encode(pairingPsk)}",
            "--epoch=$epoch",
            "--report=${report.absolutePath}",
        ).redirectErrorStream(true).start()
        // The output is read on its own thread, because the pipe fills up
        // when nobody reads it.
        val reader = Thread {
            process.inputStream.bufferedReader().forEachLine { line ->
                println("[server] $line")
            }
        }
        reader.isDaemon = true
        reader.start()
        return process
    }

    /** Waits for the server to listen. */
    private fun waitForPort(port: Int, process: Process): Boolean {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive) {
                // The server gave up. Its output says why.
                return false
            }
            try {
                Socket("127.0.0.1", port).close()
                return true
            } catch (_: Exception) {
                Thread.sleep(50)
            }
        }
        return false
    }

    /** A port that nothing uses. */
    private fun freePort(): Int {
        ServerSocket(0).use { socket -> return socket.localPort }
    }

    /** The path of the report file. */
    private fun reportPath(): String = "build/interop/report.jsonl"

    /** The lines of the report, as maps of fields. */
    private fun readReport(report: File): List<Map<String, String>> {
        if (!report.isFile) return emptyList()
        return report.readLines()
            .filter { it.isNotBlank() }
            .map { line ->
                line.split(" ")
                    .mapNotNull { part ->
                        val index = part.indexOf('=')
                        if (index < 0) null else part.substring(0, index) to part.substring(index + 1)
                    }
                    .toMap()
            }
    }

    private companion object {
        /** The size of one chunk of audio, in bytes. */
        const val CHUNK_BYTES = 19_200
    }
}
