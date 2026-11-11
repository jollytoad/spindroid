package com.sendspin.protocol.session

import com.sendspin.protocol.crypto.Base64Url
import com.sendspin.protocol.crypto.NoiseSuite
import com.sendspin.protocol.crypto.X25519
import com.sendspin.protocol.noise.Kkpsk2Handshake
import com.sendspin.protocol.noise.NoiseTransport
import com.sendspin.protocol.pairing.PairingCode
import com.sendspin.protocol.pairing.PairingWrap
import com.sendspin.protocol.pairing.cpace.CPaceResponder
import com.sendspin.protocol.protocol.AudioChunk
import com.sendspin.protocol.protocol.ClientGoodbyePayload
import com.sendspin.protocol.protocol.ClientHelloPayload
import com.sendspin.protocol.protocol.ClientInitPayload
import com.sendspin.protocol.protocol.ClientPairAuthPayload
import com.sendspin.protocol.protocol.ClientPairConfirmPayload
import com.sendspin.protocol.protocol.ClientPairFinalizePayload
import com.sendspin.protocol.protocol.ClientPairInitPayload
import com.sendspin.protocol.protocol.ClientPairRetryPayload
import com.sendspin.protocol.protocol.ClientSourceSupport
import com.sendspin.protocol.protocol.ClientStatePayload
import com.sendspin.protocol.protocol.ClientStreamEndPayload
import com.sendspin.protocol.protocol.ClientStreamStartPayload
import com.sendspin.protocol.protocol.ClientTimePayload
import com.sendspin.protocol.protocol.DeviceInfo
import com.sendspin.protocol.protocol.FragmentReassembler
import com.sendspin.protocol.protocol.Fragmenter
import com.sendspin.protocol.protocol.FrameResult
import com.sendspin.protocol.protocol.JsonOptional
import com.sendspin.protocol.protocol.MessageCodec
import com.sendspin.protocol.protocol.MessageId
import com.sendspin.protocol.protocol.MessageType
import com.sendspin.protocol.protocol.NoiseHandshakePayload
import com.sendspin.protocol.protocol.PairAbortPayload
import com.sendspin.protocol.protocol.PairMethodDescriptor
import com.sendspin.protocol.protocol.ServerActivatePayload
import com.sendspin.protocol.protocol.ServerCommandPayload
import com.sendspin.protocol.protocol.ServerHelloPayload
import com.sendspin.protocol.protocol.ServerInitPayload
import com.sendspin.protocol.protocol.ServerPairAuthPayload
import com.sendspin.protocol.protocol.ServerPairConfirmPayload
import com.sendspin.protocol.protocol.ServerPairInitPayload
import com.sendspin.protocol.protocol.ServerTimePayload
import com.sendspin.protocol.protocol.SourceCommand
import com.sendspin.protocol.protocol.SourceFeatures
import com.sendspin.protocol.protocol.SourceState
import com.sendspin.protocol.protocol.SourceStreamFormat
import com.sendspin.protocol.protocol.UnpairedAccess
import com.sendspin.protocol.psk.ClientKeyStore
import com.sendspin.protocol.psk.ClientPskResolution
import com.sendspin.protocol.psk.ClientPskResolver
import com.sendspin.protocol.psk.PskCategory
import com.sendspin.protocol.psk.PskRecord
import com.sendspin.protocol.psk.SendspinPsk
import com.sendspin.protocol.time.ClockSyncBurst
import com.sendspin.protocol.time.TimeFilter
import java.security.SecureRandom

/**
 * The client session of a SendSpin source.
 *
 * The session owns the Noise channel, the clock filter, and the state of the
 * source role. It runs the whole exchange: the cleartext handshake, the
 * hello, the clock synchronisation, and the audio stream.
 *
 * The session does not own the socket. It writes to a [SessionTransport] and
 * takes what arrives through [onTextMessage] and [onBinaryMessage]. That
 * keeps the protocol logic free of any network library, so the tests can
 * drive it directly.
 *
 * The messages fall into two kinds.
 *
 * - The cleartext handshake uses text frames. Its messages carry the full
 *   envelope, with a `type` and a `payload`.
 * - Everything after the handshake uses binary frames. The first byte of the
 *   decrypted message is its type. A type of zero means the rest is a JSON
 *   envelope, and any other type belongs to a role the source does not use.
 */
public class SourceSession(
    private val keys: ClientKeyStore,
    private val transport: SessionTransport,
    private val clock: SessionClock,
    private val listener: SourceSessionListener,
    private val configuration: SourceSessionConfiguration = SourceSessionConfiguration(),
) {
    private val codec = MessageCodec()
    private val resolver = ClientPskResolver(keys)
    private val random = SecureRandom()

    /**
     * The lock that keeps the session in order.
     *
     * The session is driven from two threads. The messages arrive on the
     * thread of the socket, and the clock is polled on a timer. Both touch
     * the clock filter and the stream flags, so they take the same lock.
     */
    private val lock = Any()

    /** The client's own identifier, which is the public key in base64url. */
    public val clientId: String = Base64Url.encode(X25519.publicKey(keys.staticPrivateKey))

    private var channel: NoiseTransport? = null
    private var reassembler = FragmentReassembler()

    /** The bytes of `client/init`, which the prologue binds the handshake to. */
    private var clientInitBytes: ByteArray? = null

    private var serverInitBytes: ByteArray? = null
    private var serverId: String? = null
    private var serverName: String? = null
    private var serverStaticKey: ByteArray? = null
    private var serverCodecs: List<String> = emptyList()

    private var activeRoles: List<String> = emptyList()
    private var activities: List<String> = emptyList()
    private var paired = false

    /** The key that matched the handshake. It decides what the session may do. */
    private var matched: PskCategory = PskCategory.SENTINEL

    /** The pending long-term key, waiting for the server to confirm it. */
    private var pendingLongTermPsk: ByteArray? = null

    private var pairingIndex = 0

    /**
     * The nonce this client contributes to the pairing code.
     *
     * It is drawn when the pairing starts and kept until the pairing ends.
     * The server never sees it until the exchange has proved the operator
     * knew the code.
     */
    private var pairingNonceB: ByteArray? = null

    /**
     * The pairing code the operator reads.
     *
     * It is null until the server's nonce has arrived, because the code
     * depends on it.
     */
    private var pairingCode: String? = null

    /** The round of the pairing attempt. The first round is one. */
    private var pairingRound = 0

    /** The CPace run of the current round. */
    private var pakeResponder: CPaceResponder? = null

    private val filter = TimeFilter()
    private val burst = ClockSyncBurst()

    /** The `client/time` exchange that is waiting for its reply. */
    private var pendingExchange: Long? = null

    /** A burst of exchanges is running. */
    private var burstRunning = false

    /**
     * The server asked for audio before the clock settled.
     *
     * The request is kept, and the stream opens when the clock converges.
     */
    private var startRequested = false

    /** The stream is open, and audio chunks are on their way. */
    private var streaming = false

    /** The state the session publishes. */
    private var currentState = SourceSessionState()

    /** The state of the session, for the user interface. */
    public val state: SourceSessionState get() = currentState

    /**
     * Starts the handshake.
     *
     * The client sends `client/init` first. Its exact bytes go into the
     * prologue of the Noise handshake, so they must not change afterwards.
     */
    public fun start() {
        val init = codec.encode(
            MessageType.CLIENT_INIT,
            ClientInitPayload(
                clientId = clientId,
                version = PROTOCOL_VERSION,
                suite = configuration.suite.protocolName,
            ),
        )
        clientInitBytes = init
        currentState = currentState.copy(
            phase = SessionPhase.HANDSHAKING,
            error = null,
            pairedServers = pairedServerNames(),
        )
        publish()
        transport.sendText(String(init, Charsets.UTF_8))
    }

    /**
     * Takes a cleartext message from the transport.
     *
     * Only the handshake uses cleartext messages. Anything else arrives as a
     * binary frame.
     */
    public fun onTextMessage(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val type = runCatching { codec.typeOf(bytes) }.getOrNull() ?: return
        synchronized(lock) {
            when (type) {
                MessageType.SERVER_INIT -> onServerInit(bytes)
                MessageType.NOISE_HANDSHAKE -> onNoiseHandshake(bytes)
                MessageType.SERVER_ERROR -> fail(SERVER_REFUSED)
                else -> Unit
            }
        }
    }

    /** Takes a binary frame from the transport. */
    public fun onBinaryMessage(bytes: ByteArray) {
        val secure = channel ?: return
        val plaintext = runCatching { secure.decrypt(bytes) }.getOrNull() ?: run {
            fail("The server sent a message that did not decrypt")
            return
        }
        when (val result = reassembler.offer(plaintext)) {
            is FrameResult.Complete -> onCompleteMessage(result.messageId, result.payload)
            is FrameResult.Malformed -> fail(result.reason)
            FrameResult.Incomplete -> Unit
        }
    }

    /** The transport closed. */
    public fun onTransportClosed() {
        synchronized(lock) {
            if (currentState.phase == SessionPhase.CLOSED) return
            currentState = currentState.copy(phase = SessionPhase.CLOSED, streaming = false)
            publish()
            listener.onClosed("The connection closed")
        }
    }

    /**
     * Hands a block of captured audio to the session.
     *
     * The block holds little-endian signed 16-bit samples. The timestamp is
     * the local time of the first sample, in microseconds.
     *
     * The session drops the block when the network has fallen behind, and
     * resumes from live capture. It sends nothing while the server has not
     * asked for audio.
     */
    public fun onCapturedAudio(pcm: ByteArray, captureTimestampMicros: Long) {
        synchronized(lock) {
            if (!streaming) return
            val now = clock.nowMicros()
            // The backlog guard: audio that waited too long is stale, and
            // the server would play it out of time. Drop it and resume
            // from live.
            if (now - captureTimestampMicros > MAX_BACKLOG_MICROS) return
            val timestamp = filter.computeServerTime(captureTimestampMicros)
            sendEncrypted(
                MessageId.SOURCE_AUDIO,
                AudioChunk.sourceBody(timestamp, pcm),
            )
        }
    }

    /**
     * Runs one step of the clock synchronisation.
     *
     * The caller runs this on a timer. An exchange waits for its reply
     * before the next one starts, because a delayed earlier message delays
     * the later ones on an ordered transport.
     */
    public fun pollClock() {
        synchronized(lock) {
            if (currentState.phase != SessionPhase.SYNCING && currentState.phase != SessionPhase.READY) {
                return
            }
            val now = clock.nowMicros()
            val pending = pendingExchange
            if (pending != null) {
                // A reply that never arrives means the server stopped
                // answering. The exchange is given a little room before the
                // session gives up.
                if (now - pending > EXCHANGE_TIMEOUT_MICROS) {
                    fail("The server stopped answering the clock")
                }
                return
            }
            if (!burstRunning) {
                // Once the filter has enough samples, the bursts settle down
                // to one every nine seconds. Until then they follow each
                // other, so the clock converges quickly.
                if (isConverged() && !burst.canStartBurst(now)) return
                burst.startBurst(now)
                burstRunning = true
            }
            sendTimeExchange()
        }
    }

    /**
     * Sends the next `client/time` exchange.
     */
    private fun sendTimeExchange() {
        val now = clock.nowMicros()
        pendingExchange = now
        sendJson(MessageType.CLIENT_TIME, ClientTimePayload(clientTransmitted = now))
    }

    /**
     * Handles the `server/init` message.
     *
     * The bytes go into the prologue of the Noise handshake, so they must be
     * the bytes that arrived, not a re-encoding.
     */
    private fun onServerInit(bytes: ByteArray) {
        val payload = runCatching {
            codec.decode<ServerInitPayload>(MessageType.SERVER_INIT, bytes)
        }.getOrNull() ?: run {
            fail("The server sent a malformed server/init")
            return
        }
        if (payload.version != PROTOCOL_VERSION) {
            fail("The server speaks protocol version ${payload.version}")
            return
        }
        val key = Base64Url.decode(payload.serverId)
        if (key == null || key.size != X25519.KEY_SIZE) {
            fail("The server sent an invalid identifier")
            return
        }
        serverInitBytes = bytes
        serverId = payload.serverId
        serverStaticKey = key
    }

    /**
     * Handles the `noise/handshake` message.
     *
     * The first one carries the server's Noise message. The client answers
     * with its own, which completes the handshake.
     */
    private fun onNoiseHandshake(bytes: ByteArray) {
        val payload = runCatching {
            codec.decode<NoiseHandshakePayload>(MessageType.NOISE_HANDSHAKE, bytes)
        }.getOrNull() ?: run {
            fail("The server sent a malformed handshake message")
            return
        }
        val message = Base64Url.decode(payload.data)
        if (message == null) {
            fail("The server sent an invalid handshake message")
            return
        }
        val prologue = clientInitBytes?.plus(serverInitBytes ?: ByteArray(0))
        if (prologue == null) {
            fail("The server sent a handshake message before its init message")
            return
        }
        val noise = Kkpsk2Handshake.responder(
            suite = configuration.suite,
            prologue = prologue,
            localStaticPrivate = keys.staticPrivateKey,
            remoteStaticPublic = serverStaticKey ?: return,
        )
        val plain = runCatching { noise.readMessage1(message) }.getOrNull() ?: run {
            fail("The server's handshake message did not decrypt")
            return
        }
        val selection = choosePsk(plain, isRehandshake = false)
        if (selection == null) {
            fail("The server named a key this client does not hold")
            return
        }
        val answer = runCatching {
            noise.writeMessage2(EMPTY_OBJECT, selection.psk)
        }.getOrNull() ?: run {
            fail("The client could not answer the handshake")
            return
        }
        val reply = codec.encode(
            MessageType.NOISE_HANDSHAKE,
            NoiseHandshakePayload(data = Base64Url.encode(answer)),
        )
        transport.sendText(String(reply, Charsets.UTF_8))
        channel = noise.split()
        matched = selection.category
        paired = matched == PskCategory.LONG_TERM
        currentState = currentState.copy(phase = SessionPhase.HELLO, paired = paired)
        publish()
    }

    /**
     * Chooses the key for the handshake.
     *
     * The server names an identifier and a category. The client compares the
     * identifier against the keys it holds. A miss in the first handshake
     * falls back to the Sentinel key, and a miss in a re-handshake fails.
     *
     * @return the choice, or null when the handshake must fail
     */
    private fun choosePsk(plain: ByteArray, isRehandshake: Boolean): PskChoice? {
        val text = runCatching { String(plain, Charsets.UTF_8) }.getOrNull() ?: return null
        val pskId = extractJsonString(text, "psk_id") ?: return null
        val categoryName = extractJsonString(text, "psk_category") ?: return null
        val category = PskCategory.fromWireName(categoryName) ?: return null
        return when (
            val resolution = resolver.resolve(
                pskId = pskId,
                category = category,
                serverId = serverId,
                isRehandshake = isRehandshake,
            )
        ) {
            is ClientPskResolution.Matched ->
                PskChoice(resolution.psk, resolution.category)

            is ClientPskResolution.SentinelFallback ->
                PskChoice(SendspinPsk.SENTINEL, PskCategory.SENTINEL)

            is ClientPskResolution.Failure -> null
        }
    }

    /**
     * Reads a string field out of a small JSON object.
     *
     * The handshake payload has two string fields and nothing else, so a
     * full parser is not worth its dependency.
     */
    private fun extractJsonString(text: String, field: String): String? {
        val key = "\"$field\""
        val start = text.indexOf(key)
        if (start < 0) return null
        val colon = text.indexOf(':', start + key.length)
        if (colon < 0) return null
        val open = text.indexOf('"', colon + 1)
        if (open < 0) return null
        val close = text.indexOf('"', open + 1)
        if (close < 0) return null
        return text.substring(open + 1, close)
    }

    /** Handles a complete binary message. */
    private fun onCompleteMessage(messageId: Int, payload: ByteArray) {
        if (messageId != MessageId.JSON) {
            // A binary message the source role does not use. The
            // specification says to ignore it.
            return
        }
        val type = runCatching { codec.typeOf(payload) }.getOrNull() ?: return
        // The clock and the stream flags are shared with the timer thread,
        // so the handlers take the same lock.
        synchronized(lock) {
            when (type) {
                MessageType.SERVER_HELLO -> onServerHello(payload)
                MessageType.SERVER_ACTIVATE -> onServerActivate(payload)
                MessageType.SERVER_TIME -> onServerTime(payload)
                MessageType.SERVER_COMMAND -> onServerCommand(payload)
                MessageType.SERVER_PAIR_INIT -> onServerPairInit(payload)
                MessageType.SERVER_PAIR_AUTH -> onServerPairAuth(payload)
                MessageType.SERVER_PAIR_CONFIRM -> onServerPairConfirm(payload)
                MessageType.SERVER_PAIR_FINALIZE -> onServerPairFinalize()
                MessageType.SERVER_UNPAIR -> onServerUnpair()
                MessageType.PAIR_ABORT -> onPairAbort(payload)
                MessageType.NOISE_HANDSHAKE -> onRehandshake(payload)
                else -> Unit
            }
        }
    }

    /** Handles the `server/hello` message, and answers with `client/hello`. */
    private fun onServerHello(payload: ByteArray) {
        val hello = runCatching {
            codec.decode<ServerHelloPayload>(MessageType.SERVER_HELLO, payload)
        }.getOrNull() ?: return
        serverName = hello.name
        serverCodecs = hello.sourceSupport?.supportedCodecs ?: emptyList()
        currentState = currentState.copy(serverName = hello.name, serverId = serverId)
        publish()
        sendHello()
    }

    /** Sends the `client/hello` message. */
    private fun sendHello() {
        sendJson(
            MessageType.CLIENT_HELLO,
            ClientHelloPayload(
                name = configuration.clientName,
                deviceInfo = configuration.deviceInfo,
                supportedRoles = listOf(SOURCE_ROLE),
                sourceSupport = ClientSourceSupport(
                    features = SourceFeatures(lineSense = false),
                ),
                supportedPairMethods = mapOf(
                    PAIRING_METHOD to PairMethodDescriptor(locations = listOf("device")),
                    DYNAMIC_PAIRING_METHOD to PairMethodDescriptor(
                        outChannels = listOf("display"),
                        formats = listOf(DIGITS_FORMAT),
                    ),
                ),
                unpairedAccess = UnpairedAccess(enabled = configuration.unpairedAccessEnabled),
            ),
        )
    }

    /** Handles the `server/activate` message. */
    private fun onServerActivate(payload: ByteArray) {
        val activate = runCatching {
            codec.decode<ServerActivatePayload>(MessageType.SERVER_ACTIVATE, payload)
        }.getOrNull() ?: return
        // A server may only declare what the key that opened the connection
        // allows. A server that asks for more gets a goodbye, because the
        // captured audio must not reach a server that has not proved
        // itself.
        val refusal = inadmissibleReason(activate)
        if (refusal != null) {
            refuseActivation(refusal)
            return
        }
        activities = activate.activities
        // The active roles persist across a later activation that leaves them
        // out, so the client keeps them until the server removes them. The
        // exception is a connection that cannot carry roles at all. A code
        // pairing runs over a connection that the Sentinel key opened, and
        // such a connection carries no roles.
        val wasSourceActive = isSourceActive()
        activeRoles = activate.activeRoles
            ?: if (isPlaybackCapable(activities)) activeRoles else emptyList()
        currentState = currentState.copy(
            activities = activities,
            activeRoles = activeRoles,
        )
        publish()
        if (activate.pairing != null) {
            onPairingRequested(activate)
        }
        if (wasSourceActive && !isSourceActive()) {
            // The source role left. The stream ends, and a new start is
            // needed before it opens again.
            endStream()
        }
        if (!wasSourceActive && isSourceActive()) {
            // The role became active. The clock has to be in sync before
            // the client reports itself available.
            currentState = currentState.copy(phase = SessionPhase.SYNCING)
            publish()
        }
        sendState()
    }

    /**
     * Handles a pairing request from the server.
     *
     * The client offers two methods. The pairing key needs a token that the
     * operator copies. The dynamic pairing code needs six digits that the
     * operator reads off this screen.
     */
    private fun onPairingRequested(activate: ServerActivatePayload) {
        val pairing = activate.pairing ?: return
        when (pairing.method) {
            PAIRING_METHOD -> startPairingKey()
            DYNAMIC_PAIRING_METHOD -> startDynamicPairing(pairing.format)
            else -> abortPairing("method_not_supported")
        }
    }

    /**
     * Starts the pairing with the pairing key.
     *
     * The server must have matched that key, or the request is refused.
     */
    private fun startPairingKey() {
        if (matched != PskCategory.PAIRING) {
            abortPairing("method_not_supported")
            return
        }
        pairingIndex += 1
        currentState = currentState.copy(pairingInProgress = true)
        publish()
        // The long-term key is drawn now, and sent on. The server stores it
        // and confirms with `server/pair-finalize`.
        val psk = ByteArray(32).also { random.nextBytes(it) }
        pendingLongTermPsk = psk
        // The two messages go back to back, without waiting in between.
        sendJson(
            MessageType.CLIENT_PAIR_INIT,
            ClientPairInitPayload(pairingIndex = pairingIndex),
        )
        sendJson(
            MessageType.CLIENT_PAIR_FINALIZE,
            ClientPairFinalizePayload(longTermPsk = Base64Url.encode(psk)),
        )
    }

    /**
     * Starts the pairing with a dynamic pairing code.
     *
     * A code pairing runs over a connection that nothing has authenticated
     * yet, so the key that opened it has to be the Sentinel key. The code
     * itself appears once the server has sent its nonce.
     */
    private fun startDynamicPairing(format: String?) {
        if (format != DIGITS_FORMAT) {
            abortPairing("method_not_supported")
            return
        }
        if (matched != PskCategory.SENTINEL) {
            abortPairing("method_not_supported")
            return
        }
        pairingIndex += 1
        pairingRound = 0
        pairingCode = null
        val nonce = PairingCode.generateNonce(random)
        pairingNonceB = nonce
        currentState = currentState.copy(pairingInProgress = true, pairingCode = null)
        publish()
        // The commitment goes out before the server's nonce is known, so the
        // server cannot steer the code to a value it already knows.
        sendJson(
            MessageType.CLIENT_PAIR_INIT,
            ClientPairInitPayload(
                pairingIndex = pairingIndex,
                commitB = Base64Url.encode(PairingCode.commit(nonce)),
            ),
        )
    }

    /**
     * Handles `server/pair-init`, which begins a round.
     *
     * The first round carries the server's nonce, and the code comes from
     * it. A later round carries none, and runs under a new session id.
     */
    private fun onServerPairInit(payload: ByteArray) {
        val message = runCatching {
            codec.decode<ServerPairInitPayload>(MessageType.SERVER_PAIR_INIT, payload)
        }.getOrNull() ?: return protocolError()
        val nonceB = pairingNonceB ?: return protocolError()
        val nonceA = message.nonceA?.let { Base64Url.decode(it) }
        if (nonceA != null && nonceA.size != PairingCode.NONCE_SIZE) return protocolError()
        val handshakeHash = channel?.handshakeHash ?: return protocolError()
        if (pairingRound == 0) {
            // The server's nonce arrives in the first round only.
            if (nonceA == null) return protocolError()
            pairingRound = 1
            val code = PairingCode.deriveDigits(handshakeHash, nonceA, nonceB)
            pairingCode = code
            currentState = currentState.copy(pairingCode = code)
            publish()
        } else {
            // A later round carries no nonce, and the code stays the same.
            if (nonceA != null) return protocolError()
            pairingRound += 1
        }
        // Every round is a fresh exchange, with fresh secrets and its own
        // session id.
        val code = pairingCode ?: return protocolError()
        val sid = PairingWrap.sessionId(handshakeHash, pairingIndex, pairingRound)
        pakeResponder = CPaceResponder(prs = code.toByteArray(Charsets.UTF_8), sid = sid)
    }

    /**
     * Handles `server/pair-auth`, which carries the server's share.
     */
    private fun onServerPairAuth(payload: ByteArray) {
        val message = runCatching {
            codec.decode<ServerPairAuthPayload>(MessageType.SERVER_PAIR_AUTH, payload)
        }.getOrNull() ?: return protocolError()
        val responder = pakeResponder ?: return protocolError()
        val peerShare = Base64Url.decode(message.pakeMsg1) ?: return protocolError()
        // The share is computed before it is sent, but it is only sent once
        // the server's share has proved to be usable. Answering an unusable
        // share would hand an unauthenticated peer something it did not earn.
        val ourShare = responder.publicShare
        if (!responder.derive(peerShare)) return protocolError()
        sendJson(
            MessageType.CLIENT_PAIR_AUTH,
            ClientPairAuthPayload(pakeMsg2 = Base64Url.encode(ourShare)),
        )
    }

    /**
     * Handles `server/pair-confirm`, which carries the server's tag.
     *
     * A tag that does not verify means the operator typed the wrong code.
     * Another round runs then, under a new session id, and the code stays
     * on the screen.
     */
    private fun onServerPairConfirm(payload: ByteArray) {
        val message = runCatching {
            codec.decode<ServerPairConfirmPayload>(MessageType.SERVER_PAIR_CONFIRM, payload)
        }.getOrNull() ?: return protocolError()
        val responder = pakeResponder ?: return protocolError()
        val nonceB = pairingNonceB ?: return protocolError()
        val serverKc = Base64Url.decode(message.serverKc) ?: return protocolError()
        if (!responder.verify(serverKc)) {
            if (pairingRound >= ROUND_LIMIT) {
                abortPairing("pairing_code_mismatch")
                return
            }
            pakeResponder = null
            sendJson(MessageType.CLIENT_PAIR_RETRY, ClientPairRetryPayload())
            return
        }
        val handshakeHash = channel?.handshakeHash ?: return protocolError()
        val sid = PairingWrap.sessionId(handshakeHash, pairingIndex, pairingRound)
        val isk = responder.isk
        // The opening of our nonce and the new long-term key travel only
        // under the key that the exchange produced.
        val wrappedNonce = PairingWrap.seal(
            configuration.suite,
            PairingWrap.wrapKey(PairingWrap.NONCE_LABEL, sid, isk),
            nonceB,
        )
        val psk = ByteArray(32).also { random.nextBytes(it) }
        val wrappedPsk = PairingWrap.seal(
            configuration.suite,
            PairingWrap.wrapKey(PairingWrap.PSK_LABEL, sid, isk),
            psk,
        )
        pendingLongTermPsk = psk
        // The two messages go back to back, without waiting in between.
        sendJson(
            MessageType.CLIENT_PAIR_CONFIRM,
            ClientPairConfirmPayload(
                clientKc = Base64Url.encode(responder.tag()),
                wrappedNonceB = Base64Url.encode(wrappedNonce),
            ),
        )
        sendJson(
            MessageType.CLIENT_PAIR_FINALIZE,
            ClientPairFinalizePayload(wrappedPsk = Base64Url.encode(wrappedPsk)),
        )
    }

    /**
     * Handles the `server/pair-finalize` message.
     *
     * The server stored the key, so the client stores its side of the
     * pairing record.
     */
    private fun onServerPairFinalize() {
        val psk = pendingLongTermPsk ?: return
        val server = serverId ?: return
        pendingLongTermPsk = null
        keys.putLongTerm(
            PskRecord(
                serverId = server,
                psk = psk,
                serverName = serverName ?: server,
                category = PskCategory.LONG_TERM,
            ),
        )
        paired = true
        clearPairing()
        currentState = currentState.copy(
            paired = true,
            pairingInProgress = false,
            pairingCode = null,
            pairedServers = pairedServerNames(),
        )
        publish()
    }

    /**
     * Handles the `server/unpair` message.
     *
     * The server dropped the pairing, so the client forgets the record.
     */
    private fun onServerUnpair() {
        val server = serverId ?: return
        keys.removeLongTerm(server)
        paired = false
        currentState = currentState.copy(
            paired = false,
            pairedServers = pairedServerNames(),
        )
        publish()
    }

    /** The names of the servers this client is paired with. */
    private fun pairedServerNames(): List<String> =
        keys.longTermRecords().map { it.serverName }

    /** Handles the `pair/abort` message. */
    private fun onPairAbort(payload: ByteArray) {
        val abort = runCatching {
            codec.decode<PairAbortPayload>(MessageType.PAIR_ABORT, payload)
        }.getOrNull() ?: return
        pendingLongTermPsk = null
        clearPairing()
        currentState = currentState.copy(
            pairingInProgress = false,
            pairingCode = null,
            error = "The pairing stopped: ${abort.reason}",
        )
        publish()
    }

    /** Aborts a pairing attempt. */
    private fun abortPairing(reason: String) {
        clearPairing()
        sendJson(MessageType.PAIR_ABORT, PairAbortPayload(reason = reason))
        currentState = currentState.copy(pairingInProgress = false, pairingCode = null)
        publish()
    }

    /**
     * Forgets everything that belongs to a pairing attempt.
     *
     * The nonce and the exchange are secret, so they go as soon as the
     * attempt ends.
     */
    private fun clearPairing() {
        pairingNonceB = null
        pairingCode = null
        pairingRound = 0
        pakeResponder = null
    }

    /**
     * Ends the connection because the server broke the protocol.
     *
     * The connection closes without an application-level message. Telling
     * an unauthenticated peer which check it failed is exactly what that
     * would leak.
     */
    private fun protocolError() {
        clearPairing()
        fail("The server broke the pairing rules")
    }

    /**
     * Handles the `server/time` message.
     *
     * The four timestamps give the offset between the two clocks, and the
     * error of the measurement.
     */
    private fun onServerTime(payload: ByteArray) {
        val time = runCatching {
            codec.decode<ServerTimePayload>(MessageType.SERVER_TIME, payload)
        }.getOrNull() ?: return
        val sent = pendingExchange ?: return
        pendingExchange = null
        if (time.clientTransmitted != sent) return
        val now = clock.nowMicros()
        // The measurement is the offset between the two clocks, and the
        // error is half the round trip.
        val measurement = ((time.serverReceived - sent) + (time.serverTransmitted - now)) / 2
        val maxError = ((now - sent) - (time.serverTransmitted - time.serverReceived)) / 2
        val sample = burst.record(measurement, maxError, now)
        if (sample == null) {
            // The burst is not finished. The next exchange goes out at once,
            // so a burst is a short run of back-to-back exchanges.
            sendTimeExchange()
            return
        }
        burstRunning = false
        // The burst feeds the filter once, with the sample that has the
        // smallest error.
        filter.update(sample.measurement, sample.maxError, sample.at)
        val converged = isConverged()
        currentState = currentState.copy(
            clockConverged = converged,
            clockErrorMicros = filter.error().toLong(),
            phase = if (converged) SessionPhase.READY else SessionPhase.SYNCING,
        )
        publish()
        if (converged && !currentState.available) {
            sendState()
        }
        if (converged && startRequested && isSourceActive()) {
            // The server asked before the clock settled. The stream can open
            // now, so its first chunk carries a correct timestamp.
            openStream()
        }
    }

    /** Handles the `server/command` message. */
    public fun onServerCommand(payload: ByteArray) {
        val command = runCatching {
            codec.decode<ServerCommandPayload>(MessageType.SERVER_COMMAND, payload)
        }.getOrNull() ?: return
        val source = command.source ?: return
        when (source.command) {
            "start" -> {
                // A start opens one stream. A second start does not restart it.
                if (streaming || startRequested) return
                if (!isSourceActive()) return
                if (!currentState.available) {
                    // The start may arrive before the clock settles. Keep the
                    // request, and open the stream when the clock converges.
                    startRequested = true
                    currentState = currentState.copy(serverRequestedAudio = true)
                    publish()
                    return
                }
                openStream()
            }

            "stop" -> {
                startRequested = false
                endStream()
            }

            else -> Unit
        }
    }

    /**
     * Opens the stream and tells the server about it.
     *
     * The format must be one the server listed. The server always accepts
     * `pcm`, and the app only produces `pcm`.
     */
    private fun openStream() {
        if (streaming) return
        if (serverCodecs.isNotEmpty() && !serverCodecs.contains(configuration.format.codec)) {
            fail("The server does not take ${configuration.format.codec}")
            return
        }
        sendJson(
            MessageType.CLIENT_STREAM_START,
            ClientStreamStartPayload(
                source = SourceStreamFormat(
                    codec = configuration.format.codec,
                    channels = configuration.format.channels,
                    sampleRate = configuration.format.sampleRate,
                    bitDepth = configuration.format.bitDepth,
                ),
            ),
        )
        startRequested = false
        streaming = true
        currentState = currentState.copy(
            streaming = true,
            serverRequestedAudio = false,
            phase = SessionPhase.STREAMING,
        )
        publish()
        listener.onStreamOpened(configuration.format)
    }

    /** Ends the stream, and tells the server. */
    private fun endStream() {
        startRequested = false
        if (!streaming) {
            if (currentState.serverRequestedAudio) {
                currentState = currentState.copy(serverRequestedAudio = false)
                publish()
            }
            return
        }
        streaming = false
        sendJson(MessageType.CLIENT_STREAM_END, ClientStreamEndPayload())
        currentState = currentState.copy(
            streaming = false,
            serverRequestedAudio = false,
            phase = SessionPhase.READY,
        )
        publish()
        listener.onStreamClosed()
    }

    /**
     * Sends the `client/state` message.
     *
     * A source reports itself available only once its clock is in sync, so
     * the server never asks it for audio at the wrong time. The state object
     * of the source role travels with it either way.
     */
    private fun sendState() {
        if (!isSourceActive()) return
        val converged = isConverged()
        sendJson(
            MessageType.CLIENT_STATE,
            ClientStatePayload(
                available = converged,
                source = JsonOptional.Present(SourceState(signal = null)),
            ),
        )
        currentState = currentState.copy(available = converged)
        publish()
    }

    /**
     * Handles an in-band re-handshake.
     *
     * The server reruns the Noise handshake in the channel, to swap the
     * session keys. The prologue is the previous handshake hash.
     */
    private fun onRehandshake(payload: ByteArray) {
        val secure = channel ?: return
        val message = runCatching {
            codec.decode<NoiseHandshakePayload>(MessageType.NOISE_HANDSHAKE, payload)
        }.getOrNull() ?: return
        val bytes = Base64Url.decode(message.data) ?: return
        val noise = Kkpsk2Handshake.responder(
            suite = configuration.suite,
            prologue = secure.handshakeHash,
            localStaticPrivate = keys.staticPrivateKey,
            remoteStaticPublic = serverStaticKey ?: return,
        )
        val plain = runCatching { noise.readMessage1(bytes) }.getOrNull() ?: run {
            fail("The server's re-handshake message did not decrypt")
            return
        }
        val selection = choosePsk(plain, isRehandshake = true)
        if (selection == null) {
            fail("The server named a key this client does not hold")
            return
        }
        val answer = runCatching { noise.writeMessage2(EMPTY_OBJECT, selection.psk) }.getOrNull()
        if (answer == null) {
            fail("The client could not answer the re-handshake")
            return
        }
        val reply = codec.encode(
            MessageType.NOISE_HANDSHAKE,
            NoiseHandshakePayload(data = Base64Url.encode(answer)),
        )
        // The reply goes out under the old keys. The first message after the
        // handshake completes uses the new ones.
        sendEncrypted(MessageId.JSON, reply)
        channel = noise.split()
        matched = selection.category
        paired = matched == PskCategory.LONG_TERM
        // The server starts its count again after every re-handshake, so the
        // client has to do the same. A pairing that follows this handshake
        // then carries index 1, not the index of an earlier attempt.
        pairingIndex = 0
        reassembler = FragmentReassembler()
        currentState = currentState.copy(paired = paired)
        publish()
    }

    /**
     * Sends a JSON message in the encrypted channel.
     *
     * The first byte of the decrypted message is its type. A type of zero
     * means the rest is a JSON envelope.
     */
    private fun sendJson(type: String, payload: Any) {
        sendEncrypted(MessageId.JSON, codec.encode(type, payload))
    }

    /**
     * Sends a message in the encrypted channel.
     *
     * The body does not carry the type byte. The framing layer adds it, and
     * splits the message when it is too large for one Noise message.
     */
    private fun sendEncrypted(type: Int, body: ByteArray) {
        val secure = channel ?: return
        for (fragment in Fragmenter.fragment(type, body)) {
            transport.sendBinary(secure.encrypt(fragment))
        }
    }

    /** Whether the clock filter has enough samples to trust its estimate. */
    private fun isConverged(): Boolean = filter.count >= CONVERGED_SAMPLES

    /** Whether the server activated the source role. */
    private fun isSourceActive(): Boolean = activeRoles.contains(SOURCE_ROLE)

    /**
     * Whether the connection may carry roles at all.
     *
     * A connection is playback-capable when its activities, with the
     * playback activity added, are an allowed set for the key that opened
     * it. A long-term key allows nothing but the playback activity. The
     * Sentinel key and the pairing key allow the playback activity only
     * when the client admits unpaired access, and this one does not. So a
     * code pairing, which runs over a connection that the Sentinel key
     * opened, carries no roles.
     */
    private fun isPlaybackCapable(declared: List<String>): Boolean =
        isAllowedActivitySet(declared + ACTIVITY_PLAYBACK, configuration.unpairedAccessEnabled)

    /**
     * Whether the server may declare this set of activities.
     *
     * The specification names the sets that each key allows. The playback
     * activity is only allowed with the Sentinel key or the pairing key
     * when the client admits unpaired access, and this client does not.
     */
    private fun isAllowedActivitySet(declared: List<String>, unpairedAccess: Boolean): Boolean {
        val set = declared.toSet()
        if (set.any { it != ACTIVITY_PLAYBACK && it != ACTIVITY_PAIRING }) return false
        return when (matched) {
            PskCategory.LONG_TERM -> set.isEmpty() || set == setOf(ACTIVITY_PLAYBACK)
            PskCategory.PAIRING, PskCategory.SENTINEL ->
                if (unpairedAccess) {
                    set.isEmpty() || set == setOf(ACTIVITY_PAIRING) ||
                        set == setOf(ACTIVITY_PLAYBACK) ||
                        set == setOf(ACTIVITY_PLAYBACK, ACTIVITY_PAIRING)
                } else {
                    set.isEmpty() || set == setOf(ACTIVITY_PAIRING)
                }
        }
    }

    /**
     * The reason to refuse the server's activation, or null when it is
     * allowed.
     *
     * The specification gives two reasons. A server that asks for the
     * playback activity on a connection that has not been paired gets
     * `pairing_required`, because the answer is to pair. A server that asks
     * for anything else gets `unauthorized`.
     */
    private fun inadmissibleReason(activate: ServerActivatePayload): String? {
        val declared = activate.activities
        val roles = activate.activeRoles ?: emptyList()
        if (isAdmissible(declared, roles, configuration.unpairedAccessEnabled)) return null
        // The answer would be a pairing when unpaired access would have made
        // the activation allowed.
        if (isAdmissible(declared, roles, unpairedAccess = true)) return "pairing_required"
        return "unauthorized"
    }

    /** Whether the activation is allowed, for the given unpaired access setting. */
    private fun isAdmissible(
        declared: List<String>,
        roles: List<String>,
        unpairedAccess: Boolean,
    ): Boolean {
        if (!isAllowedActivitySet(declared, unpairedAccess)) return false
        if (roles.isEmpty()) return true
        return isAllowedActivitySet(declared + ACTIVITY_PLAYBACK, unpairedAccess)
    }

    /**
     * Refuses the server's activation and closes the connection.
     *
     * The goodbye names the reason, so the server knows whether to pair or
     * to stay away.
     */
    private fun refuseActivation(reason: String) {
        sendJson(MessageType.CLIENT_GOODBYE, ClientGoodbyePayload(reason = reason))
        currentState = currentState.copy(
            phase = SessionPhase.CLOSED,
            streaming = false,
            error = "The server asked for more than the pairing allows",
        )
        publish()
        transport.close()
    }

    /** Sends the `client/goodbye` message and closes the transport. */
    public fun sendGoodbye(reason: String) {
        sendJson(MessageType.CLIENT_GOODBYE, ClientGoodbyePayload(reason = reason))
        currentState = currentState.copy(phase = SessionPhase.CLOSED, streaming = false)
        publish()
        transport.close()
    }

    /** Ends the session with an error. */
    private fun fail(reason: String) {
        if (currentState.phase == SessionPhase.CLOSED) return
        currentState = currentState.copy(
            phase = SessionPhase.CLOSED,
            error = reason,
            streaming = false,
        )
        publish()
        transport.close()
        listener.onClosed(reason)
    }

    /** Publishes the current state. */
    private fun publish() {
        listener.onStateChanged(currentState)
    }

    /** The key the server named, and the category it named it under. */
    private data class PskChoice(val psk: ByteArray, val category: PskCategory)

    public companion object {
        /** The version of the core message format. */
        public const val PROTOCOL_VERSION: Int = 1

        /** The role this session offers. */
        public const val SOURCE_ROLE: String = "source@v1"

        /**
         * The reason the session gives when the server refuses.
         *
         * A server that refuses the connection usually did not take the
         * cipher suite the client offered. The client uses this text to
         * recognise that case and offer the other suite.
         */
        public const val SERVER_REFUSED: String = "The server refused the connection"

        /** The pairing method this session offers. */
        public const val PAIRING_METHOD: String = "pairing_psk"

        /**
         * The pairing method that shows a code instead of a token.
         *
         * The operator reads six digits off this screen and types them into
         * the server, instead of copying a long token.
         */
        public const val DYNAMIC_PAIRING_METHOD: String = "dynamic_pairing_code"

        /** The emission format of the code, the only one this client offers. */
        public const val DIGITS_FORMAT: String = "digits"

        /** The activity that says the server wants to play audio on the client. */
        public const val ACTIVITY_PLAYBACK: String = "playback"

        /** The activity that says the server wants to pair with the client. */
        public const val ACTIVITY_PAIRING: String = "pairing"

        /**
         * How many rounds one pairing attempt may run.
         *
         * A round that ends with a wrong code is tried again, and the limit
         * is what stops the attempts.
         */
        public const val ROUND_LIMIT: Int = 20

        /**
         * The number of filter updates the clock needs before the session
         * reports itself available.
         */
        public const val CONVERGED_SAMPLES: Int = 3

        /**
         * The oldest audio the session still sends, in microseconds.
         *
         * A network stall leaves audio waiting. The server would play it
         * late, so the session drops it and resumes from live capture.
         */
        public const val MAX_BACKLOG_MICROS: Long = 500_000

        /**
         * How long a `client/time` exchange may wait for its reply, in
         * microseconds.
         */
        public const val EXCHANGE_TIMEOUT_MICROS: Long = 5_000_000

        /** The payload of the second handshake message. */
        private val EMPTY_OBJECT = "{}".toByteArray(Charsets.UTF_8)
    }
}

/**
 * The settings of a source session.
 */
public data class SourceSessionConfiguration(
    /** The name the client shows to the server. */
    public val clientName: String = "SendSpin",
    /** What the client tells the server about its device. */
    public val deviceInfo: DeviceInfo? = null,
    /** The cipher suite the client picks. */
    public val suite: NoiseSuite = NoiseSuite.AES_GCM_SHA256,
    /** The audio format the client sends. */
    public val format: SourceAudioFormat = SourceAudioFormat(),
    /**
     * Whether the client admits a server it has not paired with.
     *
     * The captured audio is a privacy-sensitive input, so the app leaves
     * this off. The server then has to pair before it may use the audio.
     */
    public val unpairedAccessEnabled: Boolean = false,
)
