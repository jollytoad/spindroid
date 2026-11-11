package com.sendspin.protocol.session

import com.sendspin.protocol.crypto.Base64Url
import com.sendspin.protocol.crypto.NoiseSuite
import com.sendspin.protocol.crypto.X25519
import com.sendspin.protocol.noise.Kkpsk2Handshake
import com.sendspin.protocol.noise.NoiseTransport
import com.sendspin.protocol.protocol.AudioChunk
import com.sendspin.protocol.protocol.ClientGoodbyePayload
import com.sendspin.protocol.protocol.ClientHelloPayload
import com.sendspin.protocol.protocol.ClientInitPayload
import com.sendspin.protocol.protocol.ClientPairInitPayload
import com.sendspin.protocol.protocol.ClientStreamStartPayload
import com.sendspin.protocol.protocol.ClientTimePayload
import com.sendspin.protocol.protocol.MessageCodec
import com.sendspin.protocol.protocol.MessageId
import com.sendspin.protocol.protocol.MessageType
import com.sendspin.protocol.protocol.NoiseHandshakePayload
import com.sendspin.protocol.protocol.PairAbortPayload
import com.sendspin.protocol.protocol.PairingParameters
import com.sendspin.protocol.protocol.ServerActivatePayload
import com.sendspin.protocol.protocol.ServerCommandPayload
import com.sendspin.protocol.protocol.ServerErrorPayload
import com.sendspin.protocol.protocol.ServerHelloPayload
import com.sendspin.protocol.protocol.ServerInitPayload
import com.sendspin.protocol.protocol.ServerPairFinalizePayload
import com.sendspin.protocol.protocol.ServerTimePayload
import com.sendspin.protocol.protocol.ServerUnpairPayload
import com.sendspin.protocol.protocol.SourceCommand
import com.sendspin.protocol.protocol.SourceSupport
import com.sendspin.protocol.psk.ClientKeyStore
import com.sendspin.protocol.psk.PskCategory
import com.sendspin.protocol.psk.PskRecord
import com.sendspin.protocol.psk.SendspinPsk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The source session, driven by a fake server.
 *
 * The tests run the whole exchange: the cleartext handshake, the hello, the
 * clock synchronisation, and the audio stream.
 */
class SourceSessionTest {
    private val codec = MessageCodec()

    /**
     * The clock the test moves by hand.
     *
     * The clock has to advance, because the filter ignores a measurement
     * that does not arrive later than the one before it. The value is
     * atomic, because the concurrency test moves it from another thread.
     */
    private val clock = TestClock()

    /**
     * The clock of the fake server.
     *
     * The two clocks run on different epochs, the way a phone and a server
     * do. The difference between them is the offset the filter measures.
     */
    private val serverEpoch = 50_000_000L

    /** How long one exchange takes, in microseconds. */
    private val roundTrip = 1_000L

    /**
     * Runs the handshake, and leaves the session ready for the hello.
     *
     * @return the session, the transport, and the server
     */
    private fun handshake(
        keys: TestKeyStore = TestKeyStore(),
        namedPskId: String = SendspinPsk.SENTINEL_ID,
        namedCategory: String = "sn",
    ): Triple<SourceSession, TestTransport, TestServer> {
        val transport = TestTransport()
        val session = SourceSession(
            keys = keys,
            transport = transport,
            clock = clock,
            listener = TestListener(),
            configuration = SourceSessionConfiguration(
                clientName = "Test Phone",
                suite = NoiseSuite.AES_GCM_SHA256,
            ),
        )
        val server = TestServer(keys, transport)
        server.namedPskId = namedPskId
        server.namedCategory = namedCategory
        session.start()
        server.startHandshake(transport.texts.last())
        session.onTextMessage(server.serverInitText)
        session.onTextMessage(server.firstHandshakeMessage())
        server.finishHandshake(transport.texts.last())
        return Triple(session, transport, server)
    }

    /**
     * Runs the handshake over a pairing record.
     *
     * A server may only activate roles on a connection that a pairing
     * opened, because the captured audio must not reach a server that has
     * not proved itself. So the tests that activate the source role use
     * this one.
     */
    private fun pairedHandshake(): Triple<SourceSession, TestTransport, TestServer> {
        val transport = TestTransport()
        val keys = TestKeyStore()
        val session = SourceSession(
            keys = keys,
            transport = transport,
            clock = clock,
            listener = TestListener(),
            configuration = SourceSessionConfiguration(
                clientName = "Test Phone",
                suite = NoiseSuite.AES_GCM_SHA256,
            ),
        )
        val server = TestServer(keys, transport)
        val psk = ByteArray(32) { (it + 50).toByte() }
        keys.putLongTerm(
            PskRecord(
                serverId = server.serverId,
                psk = psk,
                serverName = "Living Room",
                category = PskCategory.LONG_TERM,
            ),
        )
        server.namedPskId = SendspinPsk.id(psk)
        server.namedCategory = "lt"
        server.namedPsk = psk
        session.start()
        server.startHandshake(transport.texts.last())
        session.onTextMessage(server.serverInitText)
        session.onTextMessage(server.firstHandshakeMessage())
        server.finishHandshake(transport.texts.last())
        return Triple(session, transport, server)
    }

    /** Sends the server hello, so the client answers with its own. */
    private fun sayHello(session: SourceSession, server: TestServer) {
        session.onBinaryMessage(
            server.encryptJson(
                MessageType.SERVER_HELLO,
                ServerHelloPayload(
                    name = "Living Room",
                    sourceSupport = SourceSupport(supportedCodecs = listOf("flac", "pcm")),
                ),
            ),
        )
    }

    /**
     * Activates the source role on the server.
     *
     * Music Assistant declares no activities for a source. The phone is never
     * in a playback group, so the server has no playback purpose to declare.
     * The approval lives in the active roles.
     */
    private fun activateSource(session: SourceSession, server: TestServer) {
        session.onBinaryMessage(
            server.encryptJson(
                MessageType.SERVER_ACTIVATE,
                ServerActivatePayload(
                    activities = listOf(),
                    activeRoles = listOf("source@v1"),
                ),
            ),
        )
    }

    /**
     * Runs the clock exchanges until the session reports itself available.
     *
     * A burst is eight exchanges, and each reply starts the next one, so
     * the test only has to answer what arrives.
     */
    private fun convergeClock(session: SourceSession, server: TestServer) {
        repeat(8 * (SourceSession.CONVERGED_SAMPLES + 1)) {
            session.pollClock()
            val exchanges = server.timeExchanges()
            for (sent in exchanges) {
                // The reply arrives a round trip later, so the clock has
                // moved on. The filter ignores a measurement that did not
                // arrive later than the one before it.
                clock.now += roundTrip
                session.onBinaryMessage(
                    server.encryptJson(
                        MessageType.SERVER_TIME,
                        ServerTimePayload(
                            clientTransmitted = sent.clientTransmitted,
                            serverReceived = serverEpoch + sent.clientTransmitted,
                            serverTransmitted = serverEpoch + sent.clientTransmitted + 100,
                        ),
                    ),
                )
            }
        }
    }

    @Test
    fun `the handshake completes and the session waits for the hello`() {
        val (session, transport, server) = handshake()
        assertEquals(SessionPhase.HELLO, session.state.phase)

        sayHello(session, server)

        // The client answers the hello with its own.
        assertEquals(1, transport.binaries.size)
        val hello = codec.decode<ClientHelloPayload>(
            MessageType.CLIENT_HELLO,
            server.nextJson(MessageType.CLIENT_HELLO),
        )
        assertEquals(listOf("source@v1"), hello.supportedRoles)
        assertEquals("Test Phone", hello.name)
        assertFalse(hello.unpairedAccess.enabled)
    }

    @Test
    fun `the clock converges and the session reports itself available`() {
        val (session, transport, server) = pairedHandshake()
        sayHello(session, server)
        activateSource(session, server)

        convergeClock(session, server)

        assertTrue(session.state.clockConverged)
        assertTrue(session.state.available)
        assertEquals(SessionPhase.READY, session.state.phase)
    }

    @Test
    fun `the session streams audio after the server asks for it`() {
        val (session, transport, server) = pairedHandshake()
        sayHello(session, server)
        activateSource(session, server)
        convergeClock(session, server)

        session.onBinaryMessage(
            server.encryptJson(
                MessageType.SERVER_COMMAND,
                ServerCommandPayload(source = SourceCommand(command = "start")),
            ),
        )

        // The stream opens at once. The server waits only a few seconds for
        // the answer, so the client must not wait for the first audio.
        assertTrue(session.state.streaming)
        assertEquals(SessionPhase.STREAMING, session.state.phase)
        assertFalse(session.state.serverRequestedAudio)

        // The captured audio follows.
        val before = transport.binaries.size
        session.onCapturedAudio(byteArrayOf(1, 2, 3, 4), clock.now)
        assertTrue(transport.binaries.size > before)
    }

    @Test
    fun `a start command opens the stream without any audio`() {
        val (session, transport, server) = pairedHandshake()
        sayHello(session, server)
        activateSource(session, server)
        convergeClock(session, server)

        val before = transport.binaries.size
        session.onBinaryMessage(
            server.encryptJson(
                MessageType.SERVER_COMMAND,
                ServerCommandPayload(source = SourceCommand(command = "start")),
            ),
        )

        // The client answers with the format of the stream, even when no
        // audio has arrived yet.
        assertTrue(transport.binaries.size > before)
        val start = codec.decode<ClientStreamStartPayload>(
            MessageType.CLIENT_STREAM_START,
            server.nextJson(MessageType.CLIENT_STREAM_START),
        )
        assertEquals("pcm", start.source?.codec)
    }

    @Test
    fun `a start command before the clock settles opens the stream later`() {
        val (session, transport, server) = pairedHandshake()
        sayHello(session, server)
        activateSource(session, server)

        // The server asks before the clock is in sync. The request is kept.
        session.onBinaryMessage(
            server.encryptJson(
                MessageType.SERVER_COMMAND,
                ServerCommandPayload(source = SourceCommand(command = "start")),
            ),
        )
        assertFalse(session.state.streaming)
        assertTrue(session.state.serverRequestedAudio)

        convergeClock(session, server)

        assertTrue(session.state.streaming)
        assertFalse(session.state.serverRequestedAudio)
        val start = codec.decode<ClientStreamStartPayload>(
            MessageType.CLIENT_STREAM_START,
            server.nextJson(MessageType.CLIENT_STREAM_START),
        )
        assertEquals("pcm", start.source?.codec)
    }

    @Test
    fun `a stop command drops a start that waits for the clock`() {
        val (session, transport, server) = pairedHandshake()
        sayHello(session, server)
        activateSource(session, server)

        session.onBinaryMessage(
            server.encryptJson(
                MessageType.SERVER_COMMAND,
                ServerCommandPayload(source = SourceCommand(command = "start")),
            ),
        )
        assertTrue(session.state.serverRequestedAudio)

        session.onBinaryMessage(
            server.encryptJson(
                MessageType.SERVER_COMMAND,
                ServerCommandPayload(source = SourceCommand(command = "stop")),
            ),
        )
        assertFalse(session.state.serverRequestedAudio)

        // The clock settles later, and the stream stays closed.
        convergeClock(session, server)
        assertFalse(session.state.streaming)
    }

    @Test
    fun `the session does not stream before the server asks for it`() {
        val (session, transport, server) = pairedHandshake()
        sayHello(session, server)
        activateSource(session, server)
        convergeClock(session, server)

        val before = transport.binaries.size
        session.onCapturedAudio(byteArrayOf(1, 2, 3, 4), clock.now)
        assertEquals(before, transport.binaries.size)
        assertFalse(session.state.streaming)
    }

    @Test
    fun `the stream ends when the server stops it`() {
        val (session, transport, server) = pairedHandshake()
        sayHello(session, server)
        activateSource(session, server)
        convergeClock(session, server)
        session.onBinaryMessage(
            server.encryptJson(
                MessageType.SERVER_COMMAND,
                ServerCommandPayload(source = SourceCommand(command = "start")),
            ),
        )
        session.onCapturedAudio(byteArrayOf(1, 2, 3, 4), clock.now)
        assertTrue(session.state.streaming)

        session.onBinaryMessage(
            server.encryptJson(
                MessageType.SERVER_COMMAND,
                ServerCommandPayload(source = SourceCommand(command = "stop")),
            ),
        )
        assertFalse(session.state.streaming)
        assertEquals(SessionPhase.READY, session.state.phase)
    }

    @Test
    fun `the stream ends when the source role is removed`() {
        val (session, transport, server) = pairedHandshake()
        sayHello(session, server)
        activateSource(session, server)
        convergeClock(session, server)
        session.onBinaryMessage(
            server.encryptJson(
                MessageType.SERVER_COMMAND,
                ServerCommandPayload(source = SourceCommand(command = "start")),
            ),
        )
        session.onCapturedAudio(byteArrayOf(1, 2, 3, 4), clock.now)
        assertTrue(session.state.streaming)

        // The server takes the source role away.
        session.onBinaryMessage(
            server.encryptJson(
                MessageType.SERVER_ACTIVATE,
                ServerActivatePayload(activities = listOf(), activeRoles = listOf()),
            ),
        )
        assertFalse(session.state.streaming)
    }

    @Test
    fun `the stream format is announced when the stream opens`() {
        val (session, transport, server) = pairedHandshake()
        sayHello(session, server)
        activateSource(session, server)
        convergeClock(session, server)
        session.onBinaryMessage(
            server.encryptJson(
                MessageType.SERVER_COMMAND,
                ServerCommandPayload(source = SourceCommand(command = "start")),
            ),
        )

        val start = codec.decode<ClientStreamStartPayload>(
            MessageType.CLIENT_STREAM_START,
            server.nextJson(MessageType.CLIENT_STREAM_START),
        )
        assertEquals("pcm", start.source?.codec)
        assertEquals(48000, start.source?.sampleRate)
        assertEquals(2, start.source?.channels)
    }

    @Test
    fun `the audio chunk carries the server time of the first sample`() {
        val (session, transport, server) = pairedHandshake()
        sayHello(session, server)
        activateSource(session, server)
        convergeClock(session, server)
        session.onBinaryMessage(
            server.encryptJson(
                MessageType.SERVER_COMMAND,
                ServerCommandPayload(source = SourceCommand(command = "start")),
            ),
        )
        session.onCapturedAudio(byteArrayOf(9, 9, 9, 9), clock.now)

        val sent = server.nextBinary(MessageId.SOURCE_AUDIO)
        assertEquals(MessageId.SOURCE_AUDIO.toByte(), sent[0])
        val parsed = AudioChunk.parseSource(sent)
        // The clock filter maps the local capture time onto the server's
        // clock, so the timestamp moves by the measured offset.
        assertNotNull(parsed.timestampMicros)
        assertTrue(parsed.timestampMicros != clock.now)
    }

    @Test
    fun `the pairing flow stores the long term key`() {
        val keys = TestKeyStore()
        val (session, transport, server) = handshake(
            keys = keys,
            namedPskId = SendspinPsk.id(keys.pairingPsk),
            namedCategory = "pr",
        )
        sayHello(session, server)
        session.onBinaryMessage(
            server.encryptJson(
                MessageType.SERVER_ACTIVATE,
                ServerActivatePayload(
                    activities = listOf("pairing"),
                    activeRoles = listOf(),
                    pairing = PairingParameters(method = "pairing_psk"),
                ),
            ),
        )
        assertTrue(session.state.pairingInProgress)

        // The client sends the pair-init and the pair-finalize back to back.
        val init = codec.decode<ClientPairInitPayload>(
            MessageType.CLIENT_PAIR_INIT,
            server.nextJson(MessageType.CLIENT_PAIR_INIT),
        )
        assertEquals(1, init.pairingIndex)
        server.nextJson(MessageType.CLIENT_PAIR_FINALIZE)

        // The server confirms, and the client stores the record.
        session.onBinaryMessage(
            server.encryptJson(
                MessageType.SERVER_PAIR_FINALIZE,
                ServerPairFinalizePayload(),
            ),
        )
        assertEquals(1, keys.records.size)
        assertTrue(session.state.paired)
        assertFalse(session.state.pairingInProgress)
    }

    @Test
    fun `a pairing request is refused when the key does not match`() {
        val keys = TestKeyStore()
        // The server names the sentinel, so the session is not on the pairing
        // key, and the pairing request must be refused.
        val (session, transport, server) = handshake(keys = keys)
        sayHello(session, server)
        session.onBinaryMessage(
            server.encryptJson(
                MessageType.SERVER_ACTIVATE,
                ServerActivatePayload(
                    activities = listOf("pairing"),
                    activeRoles = listOf(),
                    pairing = PairingParameters(method = "pairing_psk"),
                ),
            ),
        )
        assertFalse(session.state.pairingInProgress)
        assertEquals(0, keys.records.size)

        // The client answers with a pair abort.
        val abort = codec.decode<PairAbortPayload>(
            MessageType.PAIR_ABORT,
            server.nextJson(MessageType.PAIR_ABORT),
        )
        assertEquals("method_not_supported", abort.reason)
    }

    @Test
    fun `the session ignores a message it does not know`() {
        val (session, transport, server) = handshake()
        session.onBinaryMessage(
            server.encryptJson(
                MessageType.SERVER_ERROR,
                ServerErrorPayload(reason = "malformed"),
            ),
        )
        assertEquals(SessionPhase.HELLO, session.state.phase)
    }

    @Test
    fun `the session survives a clock poll and audio at the same time`() {
        val (session, transport, server) = pairedHandshake()
        sayHello(session, server)
        activateSource(session, server)

        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val exchanges = java.util.concurrent.atomic.AtomicInteger(0)

        // The server answers the clock exchanges.
        val serverThread = Thread {
            while (running.get()) {
                for (sent in server.timeExchanges()) {
                    exchanges.incrementAndGet()
                    clock.now += roundTrip
                    session.onBinaryMessage(
                        server.encryptJson(
                            MessageType.SERVER_TIME,
                            ServerTimePayload(
                                clientTransmitted = sent.clientTransmitted,
                                serverReceived = serverEpoch + sent.clientTransmitted,
                                serverTransmitted = serverEpoch + sent.clientTransmitted + 100,
                            ),
                        ),
                    )
                }
                Thread.sleep(1)
            }
        }

        // The timer polls the clock, the way the app does. It spins, so
        // the timer and the socket are as close to each other as they can
        // get.
        val timerThread = Thread {
            while (running.get()) {
                session.pollClock()
            }
        }

        // The capture feeds audio, the way the app does.
        val captureThread = Thread {
            while (running.get()) {
                session.onCapturedAudio(ByteArray(64), clock.now)
                Thread.sleep(1)
            }
        }

        serverThread.start()
        timerThread.start()
        captureThread.start()

        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline && !session.state.clockConverged) {
            Thread.sleep(5)
        }
        running.set(false)
        serverThread.join(2000)
        timerThread.join(2000)
        captureThread.join(2000)

        // The clock still converges, and the cipher stayed in order.
        assertTrue(session.state.clockConverged, "The clock did not converge")
        assertTrue(session.state.available, "The session did not report itself available")
        // Three bursts of eight exchanges are enough. More than that means
        // the timer and the socket sent exchanges over each other.
        assertTrue(
            exchanges.get() <= 8 * (SourceSession.CONVERGED_SAMPLES + 2),
            "The session sent ${exchanges.get()} exchanges, which is more than the bursts need",
        )
    }

    @Test
    fun `the roles are dropped when the connection can no longer carry them`() {
        val (session, transport, server) = pairedHandshake()
        sayHello(session, server)
        activateSource(session, server)
        assertEquals(listOf("source@v1"), session.state.activeRoles)

        // The server re-keys the session onto the Sentinel key, which is what
        // a code pairing does. The session is then on a connection that
        // cannot carry roles.
        session.onBinaryMessage(
            server.startRehandshake(SendspinPsk.SENTINEL_ID, "sn"),
        )
        val answer = server.nextJson(MessageType.NOISE_HANDSHAKE)
        server.finishRehandshake(answer, SendspinPsk.SENTINEL)

        // The server leaves the roles out, and the client has to forget the
        // ones it had.
        session.onBinaryMessage(
            server.encryptJson(
                MessageType.SERVER_ACTIVATE,
                ServerActivatePayload(activities = listOf("pairing"), activeRoles = null),
            ),
        )
        assertEquals(emptyList<String>(), session.state.activeRoles)
        assertNotNull(transport)
    }

    @Test
    fun `the server is refused when it asks for the audio without a pairing`() {
        val (session, transport, server) = handshake()
        sayHello(session, server)

        // The connection is on the Sentinel key, and the client admits no
        // unpaired access. The server may not ask for the audio.
        session.onBinaryMessage(
            server.encryptJson(
                MessageType.SERVER_ACTIVATE,
                ServerActivatePayload(
                    activities = listOf("playback"),
                    activeRoles = listOf("source@v1"),
                ),
            ),
        )
        val goodbye = codec.decode<ClientGoodbyePayload>(
            MessageType.CLIENT_GOODBYE,
            server.nextJson(MessageType.CLIENT_GOODBYE),
        )
        assertEquals("pairing_required", goodbye.reason)
        assertEquals(SessionPhase.CLOSED, session.state.phase)
        assertNotNull(transport)
    }

    @Test
    fun `the server is refused when it declares an activity set that is not allowed`() {
        val (session, transport, server) = pairedHandshake()
        sayHello(session, server)

        // A paired connection allows the playback activity and nothing else
        // beside it.
        session.onBinaryMessage(
            server.encryptJson(
                MessageType.SERVER_ACTIVATE,
                ServerActivatePayload(
                    activities = listOf("playback", "pairing"),
                    activeRoles = listOf("source@v1"),
                ),
            ),
        )
        val goodbye = codec.decode<ClientGoodbyePayload>(
            MessageType.CLIENT_GOODBYE,
            server.nextJson(MessageType.CLIENT_GOODBYE),
        )
        assertEquals("unauthorized", goodbye.reason)
        assertEquals(SessionPhase.CLOSED, session.state.phase)
        assertNotNull(transport)
    }

    @Test
    fun `the session sends a goodbye when it is asked to stop`() {        val (session, transport, server) = handshake()
        session.sendGoodbye("user_request")
        assertEquals(SessionPhase.CLOSED, session.state.phase)
    }

    @Test
    fun `the session streams when the server declares no activities`() {
        val (session, transport, server) = pairedHandshake()
        sayHello(session, server)

        // Music Assistant holds a source with no activities. The phone is
        // never in a playback group, so there is no playback purpose to
        // declare. The server still activates the source role, and that is
        // what approves the phone.
        session.onBinaryMessage(
            server.encryptJson(
                MessageType.SERVER_ACTIVATE,
                ServerActivatePayload(activities = listOf(), activeRoles = listOf("source@v1")),
            ),
        )
        convergeClock(session, server)
        session.onBinaryMessage(
            server.encryptJson(
                MessageType.SERVER_COMMAND,
                ServerCommandPayload(source = SourceCommand(command = "start")),
            ),
        )
        session.onCapturedAudio(byteArrayOf(1, 2, 3, 4), clock.now)

        assertTrue(session.state.streaming)
        val sent = server.nextBinary(MessageId.SOURCE_AUDIO)
        assertEquals(MessageId.SOURCE_AUDIO.toByte(), sent[0])
    }

    @Test
    fun `the session forgets the pairing when the server drops it`() {
        val keys = TestKeyStore()
        val (session, transport, server) = handshake(
            keys = keys,
            namedPskId = SendspinPsk.id(keys.pairingPsk),
            namedCategory = "pr",
        )
        sayHello(session, server)
        session.onBinaryMessage(
            server.encryptJson(
                MessageType.SERVER_ACTIVATE,
                ServerActivatePayload(
                    activities = listOf("pairing"),
                    activeRoles = listOf(),
                    pairing = PairingParameters(method = "pairing_psk"),
                ),
            ),
        )
        server.nextJson(MessageType.CLIENT_PAIR_INIT)
        server.nextJson(MessageType.CLIENT_PAIR_FINALIZE)
        session.onBinaryMessage(
            server.encryptJson(MessageType.SERVER_PAIR_FINALIZE, ServerPairFinalizePayload()),
        )
        assertEquals(1, keys.records.size)
        assertTrue(session.state.paired)

        // The server drops the pairing.
        session.onBinaryMessage(
            server.encryptJson(MessageType.SERVER_UNPAIR, ServerUnpairPayload()),
        )
        assertEquals(0, keys.records.size)
        assertFalse(session.state.paired)
    }

    @Test
    fun `the session answers a re-handshake`() {
        val keys = TestKeyStore()
        val (session, transport, server) = handshake(keys = keys)
        sayHello(session, server)

        // The server runs a new handshake in the channel, to swap the keys.
        // The prologue is the hash of the previous handshake.
        val longTerm = ByteArray(32) { (it + 200).toByte() }
        keys.putLongTerm(
            PskRecord(
                serverId = server.serverId,
                psk = longTerm,
                serverName = "Living Room",
                category = PskCategory.LONG_TERM,
            ),
        )
        session.onBinaryMessage(
            server.startRehandshake(SendspinPsk.id(longTerm), "lt"),
        )

        // The client answers with its own handshake message, under the old
        // keys.
        val answer = server.nextJson(MessageType.NOISE_HANDSHAKE)
        server.finishRehandshake(answer, longTerm)

        // The session is now on the long-term key, so it counts as paired.
        assertTrue(session.state.paired)

        // The server may now activate the source role.
        activateSource(session, server)
        assertEquals(listOf("source@v1"), session.state.activeRoles)
    }

    @Test
    fun `a re-handshake resets the pairing index`() {
        val keys = TestKeyStore()
        val (session, transport, server) = handshake(
            keys = keys,
            namedPskId = SendspinPsk.id(keys.pairingPsk),
            namedCategory = "pr",
        )
        sayHello(session, server)

        // The first attempt carries index 1.
        session.onBinaryMessage(
            server.encryptJson(
                MessageType.SERVER_ACTIVATE,
                ServerActivatePayload(
                    activities = listOf("pairing"),
                    activeRoles = listOf(),
                    pairing = PairingParameters(method = "pairing_psk"),
                ),
            ),
        )
        val first = codec.decode<ClientPairInitPayload>(
            MessageType.CLIENT_PAIR_INIT,
            server.nextJson(MessageType.CLIENT_PAIR_INIT),
        )
        assertEquals(1, first.pairingIndex)
        server.nextJson(MessageType.CLIENT_PAIR_FINALIZE)
        session.onBinaryMessage(
            server.encryptJson(MessageType.SERVER_PAIR_FINALIZE, ServerPairFinalizePayload()),
        )

        // The server starts its count again after a re-handshake. A second
        // attempt therefore carries index 1, not 2.
        session.onBinaryMessage(
            server.startRehandshake(SendspinPsk.id(keys.pairingPsk), "pr"),
        )
        val answer = server.nextJson(MessageType.NOISE_HANDSHAKE)
        server.finishRehandshake(answer, keys.pairingPsk)

        session.onBinaryMessage(
            server.encryptJson(
                MessageType.SERVER_ACTIVATE,
                ServerActivatePayload(
                    activities = listOf("pairing"),
                    activeRoles = listOf(),
                    pairing = PairingParameters(method = "pairing_psk"),
                ),
            ),
        )
        val second = codec.decode<ClientPairInitPayload>(
            MessageType.CLIENT_PAIR_INIT,
            server.nextJson(MessageType.CLIENT_PAIR_INIT),
        )
        assertEquals(1, second.pairingIndex)
    }

    @Test
    fun `a re-handshake fails when the client does not hold the key`() {
        val keys = TestKeyStore()
        val (session, transport, server) = handshake(keys = keys)
        sayHello(session, server)

        // The server names a key the client never held. A re-handshake has
        // no Sentinel fallback, so the session must fail.
        session.onBinaryMessage(
            server.startRehandshake(SendspinPsk.id(ByteArray(32) { 0x42 }), "lt"),
        )
        assertEquals(SessionPhase.CLOSED, session.state.phase)
    }
}
