package com.sendspin.protocol.session

import com.sendspin.protocol.crypto.Base64Url
import com.sendspin.protocol.crypto.NoiseSuite
import com.sendspin.protocol.crypto.X25519
import com.sendspin.protocol.noise.Kkpsk2Handshake
import com.sendspin.protocol.noise.NoiseTransport
import com.sendspin.protocol.protocol.AudioChunk
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

    /** A clock the test moves by hand. */
    private class TestClock(start: Long = 1_000_000L) : SessionClock {
        private val value = java.util.concurrent.atomic.AtomicLong(start)

        /** The current time of the clock. */
        var now: Long
            get() = value.get()
            set(value) {
                this.value.set(value)
            }

        override fun nowMicros(): Long = value.get()
    }

    /** A transport that records what the session sends. */
    private class TestTransport : SessionTransport {
        val texts = mutableListOf<String>()

        /**
         * The binary messages the session sent.
         *
         * The list is copied on write, because the concurrency test sends
         * from more than one thread.
         */
        val binaries = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()

        override fun sendText(text: String) {
            texts += text
        }

        override fun sendBinary(bytes: ByteArray) {
            binaries += bytes
        }

        override fun close() = Unit
    }

    /** The key store the test uses. */
    private class TestKeyStore(
        override val staticPrivateKey: ByteArray = ByteArray(32) { (it + 1).toByte() },
        override val pairingPsk: ByteArray = ByteArray(32) { (it + 100).toByte() },
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

    /** A listener that records the state the session publishes. */
    private class TestListener : SourceSessionListener {
        val states = mutableListOf<SourceSessionState>()
        var closedReason: String? = null
        var openedFormat: SourceAudioFormat? = null
        var closedStream = false

        override fun onStateChanged(state: SourceSessionState) {
            states += state
        }

        override fun onClosed(reason: String) {
            closedReason = reason
        }

        override fun onStreamOpened(format: SourceAudioFormat) {
            openedFormat = format
        }

        override fun onStreamClosed() {
            closedStream = true
        }
    }

    /**
     * The payload of the first Noise handshake message.
     *
     * The server names the key it wants, and the category it holds it
     * under.
     */
    private fun handshakePayload(pskId: String, category: String): String =
        "{\"psk_id\":\"$pskId\",\"psk_category\":\"$category\"}"

    /** A message the client sent, after the server opened it. */
    private sealed interface DecryptedMessage {
        /** A JSON envelope. */
        data class Json(val type: String, val envelope: ByteArray) : DecryptedMessage

        /** A message of another role. */
        data class Binary(val type: Int, val plaintext: ByteArray) : DecryptedMessage
    }

    /**
     * The test's server.
     *
     * It plays the server side of the protocol: it is the Noise initiator,
     * it sends the cleartext init and handshake messages, and it encrypts
     * everything that follows.
     */
    private inner class TestServer(
        private val keys: TestKeyStore,
        private val transport: TestTransport,
        private val suite: NoiseSuite = NoiseSuite.AES_GCM_SHA256,
    ) {
        val serverStatic = ByteArray(32) { (it + 200).toByte() }
        val serverId = Base64Url.encode(X25519.publicKey(serverStatic))

        /** The key the server names in its first handshake message. */
        var namedPskId: String = SendspinPsk.SENTINEL_ID

        /** The category the server names. */
        var namedCategory: String = "sn"

        private var handshake: Kkpsk2Handshake? = null
        private var rehandshake: Kkpsk2Handshake? = null
        private var channel: NoiseTransport? = null

        /**
         * How many of the client's messages the server has opened.
         *
         * The Noise cipher counts the messages it carries, so the server has
         * to open them in the order they arrived.
         */
        private var opened = 0

        /** The messages the server has opened, in the order they arrived. */
        private val queue = mutableListOf<DecryptedMessage>()

        /** The server's `server/init` text. */
        val serverInitText: String = String(
            codec.encode(
                MessageType.SERVER_INIT,
                ServerInitPayload(serverId = serverId, version = 1),
            ),
            Charsets.UTF_8,
        )

        /**
         * The client's `client/init` text, as the client sent it.
         *
         * The server needs the client's static public key to run a
         * re-handshake, and it takes it from this message.
         */
        var clientInitText: String = ""
            private set

        /**
         * Runs the handshake up to the point where the client answers.
         *
         * The prologue binds the handshake to the exact bytes of the two
         * init messages, so the server uses the text it received.
         */
        fun startHandshake(clientInit: String) {
            clientInitText = clientInit
            val clientStatic = Base64Url.decode(
                codec.decode<ClientInitPayload>(
                    MessageType.CLIENT_INIT,
                    clientInit.toByteArray(Charsets.UTF_8),
                ).clientId,
            )!!
            val prologue = clientInit.toByteArray(Charsets.UTF_8) +
                serverInitText.toByteArray(Charsets.UTF_8)
            handshake = Kkpsk2Handshake.initiator(
                suite = suite,
                prologue = prologue,
                localStaticPrivate = serverStatic,
                remoteStaticPublic = clientStatic,
            )
        }

        /** The first handshake message, as the server sends it. */
        fun firstHandshakeMessage(): String {
            val noise = handshake!!
            val message = noise.writeMessage1(
                handshakePayload(namedPskId, namedCategory).toByteArray(Charsets.UTF_8),
            )
            return String(
                codec.encode(
                    MessageType.NOISE_HANDSHAKE,
                    NoiseHandshakePayload(data = Base64Url.encode(message)),
                ),
                Charsets.UTF_8,
            )
        }

        /** Finishes the handshake with the client's answer. */
        fun finishHandshake(clientAnswer: String) {
            val noise = handshake!!
            val answer = Base64Url.decode(
                codec.decode<NoiseHandshakePayload>(
                    MessageType.NOISE_HANDSHAKE,
                    clientAnswer.toByteArray(Charsets.UTF_8),
                ).data,
            )!!
            val psk = when (namedCategory) {
                "pr" -> keys.pairingPsk
                else -> SendspinPsk.SENTINEL
            }
            noise.readMessage2(answer, psk)
            channel = noise.split()
        }

        /** Encrypts a JSON message the way the server sends it. */
        fun encryptJson(type: String, payload: Any): ByteArray {
            val envelope = codec.encode(type, payload)
            return channel!!.encrypt(byteArrayOf(MessageId.JSON.toByte()) + envelope)
        }

        /**
         * Opens every message the client has sent so far.
         *
         * The messages have to be opened in the order they arrived, because
         * the cipher counts them. The opened messages stay in the queue, so
         * the test can look at them in any order.
         */
        fun drain() {
            while (opened < transport.binaries.size) {
                val bytes = transport.binaries[opened]
                opened += 1
                val plaintext = channel!!.decrypt(bytes)
                val type = plaintext[0].toInt() and 0xFF
                if (type == MessageId.JSON) {
                    val envelope = plaintext.copyOfRange(1, plaintext.size)
                    queue += DecryptedMessage.Json(codec.typeOf(envelope), envelope)
                } else {
                    queue += DecryptedMessage.Binary(type, plaintext)
                }
            }
        }

        /**
         * Opens the messages and returns the first JSON one of the given type.
         *
         * The message leaves the queue, so a second call finds the next one.
         */
        fun nextJson(type: String): ByteArray {
            drain()
            val index = queue.indexOfFirst {
                it is DecryptedMessage.Json && it.type == type
            }
            if (index < 0) error("The client sent no $type")
            val found = queue.removeAt(index) as DecryptedMessage.Json
            return found.envelope
        }

        /**
         * Opens the messages and returns the first binary one of the given type.
         *
         * The message leaves the queue, so a second call finds the next one.
         */
        fun nextBinary(type: Int): ByteArray {
            drain()
            val index = queue.indexOfFirst {
                it is DecryptedMessage.Binary && it.type == type
            }
            if (index < 0) error("The client sent no message of type $type")
            val found = queue.removeAt(index) as DecryptedMessage.Binary
            return found.plaintext
        }

        /**
         * Opens the messages and returns the clock exchanges among them.
         *
         * The exchanges leave the queue, so each one is answered once.
         */
        fun timeExchanges(): List<ClientTimePayload> {
            drain()
            val out = mutableListOf<ClientTimePayload>()
            val iterator = queue.iterator()
            while (iterator.hasNext()) {
                val message = iterator.next()
                if (message is DecryptedMessage.Json && message.type == MessageType.CLIENT_TIME) {
                    out += codec.decode<ClientTimePayload>(
                        MessageType.CLIENT_TIME,
                        message.envelope,
                    )
                    iterator.remove()
                }
            }
            return out
        }

        /**
         * Starts a re-handshake, and returns the message to send in-band.
         *
         * A re-handshake runs inside the encrypted channel. Its prologue is
         * the hash of the previous handshake.
         */
        fun startRehandshake(pskId: String, category: String): ByteArray {
            val clientStatic = Base64Url.decode(
                codec.decode<ClientInitPayload>(
                    MessageType.CLIENT_INIT,
                    clientInitText.toByteArray(Charsets.UTF_8),
                ).clientId,
            )!!
            val noise = Kkpsk2Handshake.initiator(
                suite = suite,
                prologue = channel!!.handshakeHash,
                localStaticPrivate = serverStatic,
                remoteStaticPublic = clientStatic,
            )
            rehandshake = noise
            val message = noise.writeMessage1(
                handshakePayload(pskId, category).toByteArray(Charsets.UTF_8),
            )
            return encryptJson(
                MessageType.NOISE_HANDSHAKE,
                NoiseHandshakePayload(data = Base64Url.encode(message)),
            )
        }

        /** Finishes the re-handshake with the client's answer. */
        fun finishRehandshake(clientAnswer: ByteArray, psk: ByteArray) {
            val noise = rehandshake!!
            val envelope = codec.decode<NoiseHandshakePayload>(
                MessageType.NOISE_HANDSHAKE,
                clientAnswer,
            )
            noise.readMessage2(Base64Url.decode(envelope.data)!!, psk)
            channel = noise.split()
        }
    }

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

    /** Activates the source role on the server. */
    private fun activateSource(session: SourceSession, server: TestServer) {
        session.onBinaryMessage(
            server.encryptJson(
                MessageType.SERVER_ACTIVATE,
                ServerActivatePayload(
                    activities = listOf("playback"),
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
        val (session, transport, server) = handshake()
        sayHello(session, server)
        activateSource(session, server)

        convergeClock(session, server)

        assertTrue(session.state.clockConverged)
        assertTrue(session.state.available)
        assertEquals(SessionPhase.READY, session.state.phase)
    }

    @Test
    fun `the session streams audio after the server asks for it`() {
        val (session, transport, server) = handshake()
        sayHello(session, server)
        activateSource(session, server)
        convergeClock(session, server)

        session.onBinaryMessage(
            server.encryptJson(
                MessageType.SERVER_COMMAND,
                ServerCommandPayload(source = SourceCommand(command = "start")),
            ),
        )
        assertTrue(session.state.serverRequestedAudio)

        // The first captured block opens the stream.
        val before = transport.binaries.size
        session.onCapturedAudio(byteArrayOf(1, 2, 3, 4), clock.now)
        assertTrue(transport.binaries.size > before)
        assertTrue(session.state.streaming)
        assertEquals(SessionPhase.STREAMING, session.state.phase)
    }

    @Test
    fun `the session does not stream before the server asks for it`() {
        val (session, transport, server) = handshake()
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
        val (session, transport, server) = handshake()
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
        val (session, transport, server) = handshake()
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
        val (session, transport, server) = handshake()
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
        val (session, transport, server) = handshake()
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
        val (session, transport, server) = handshake()
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
    fun `the session sends a goodbye when it is asked to stop`() {
        val (session, transport, server) = handshake()
        session.sendGoodbye("user_request")
        assertEquals(SessionPhase.CLOSED, session.state.phase)
    }

    @Test
    fun `the session waits for the operator to approve it`() {
        val (session, transport, server) = handshake()
        sayHello(session, server)

        // The server holds the connection with empty activities until the
        // operator approves the client.
        session.onBinaryMessage(
            server.encryptJson(
                MessageType.SERVER_ACTIVATE,
                ServerActivatePayload(activities = listOf(), activeRoles = listOf()),
            ),
        )
        assertTrue(session.state.awaitingApproval)

        // The operator approves it, and the activities follow.
        session.onBinaryMessage(
            server.encryptJson(
                MessageType.SERVER_ACTIVATE,
                ServerActivatePayload(activities = listOf("playback"), activeRoles = listOf()),
            ),
        )
        assertFalse(session.state.awaitingApproval)
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
        activateSource(session, server)

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
