package com.sendspin.protocol.session

import com.sendspin.protocol.crypto.Base64Url
import com.sendspin.protocol.crypto.NoiseSuite
import com.sendspin.protocol.crypto.X25519
import com.sendspin.protocol.noise.Kkpsk2Handshake
import com.sendspin.protocol.noise.NoiseTransport
import com.sendspin.protocol.protocol.ClientInitPayload
import com.sendspin.protocol.protocol.MessageCodec
import com.sendspin.protocol.protocol.MessageId
import com.sendspin.protocol.protocol.MessageType
import com.sendspin.protocol.protocol.NoiseHandshakePayload
import com.sendspin.protocol.protocol.ServerInitPayload
import com.sendspin.protocol.psk.ClientKeyStore
import com.sendspin.protocol.psk.PskRecord
import com.sendspin.protocol.psk.SendspinPsk
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * The pieces that the session tests share.
 *
 * A fake server, a transport that records what the client sent, a clock that
 * the test moves by hand, a key store and a listener.
 */

/** A clock the test moves by hand. */
internal class TestClock(start: Long = 1_000_000L) : SessionClock {
    private val value = AtomicLong(start)

    /** The current time of the clock. */
    var now: Long
        get() = value.get()
        set(value) {
            this.value.set(value)
        }

    override fun nowMicros(): Long = value.get()
}

/** A transport that records what the session sends. */
internal class TestTransport : SessionTransport {
    val texts = mutableListOf<String>()

    /**
     * The binary messages the session sent.
     *
     * The list is copied on write, because a test may send from more than
     * one thread.
     */
    val binaries = CopyOnWriteArrayList<ByteArray>()

    override fun sendText(text: String) {
        texts += text
    }

    override fun sendBinary(bytes: ByteArray) {
        binaries += bytes
    }

    override fun close() = Unit
}

/** The key store the tests use. */
internal class TestKeyStore(
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
internal class TestListener : SourceSessionListener {
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

/** A message the client sent, after the server opened it. */
internal sealed interface DecryptedMessage {
    /** A JSON envelope. */
    data class Json(val type: String, val envelope: ByteArray) : DecryptedMessage

    /** A message of another role. */
    data class Binary(val type: Int, val plaintext: ByteArray) : DecryptedMessage
}

/**
 * The test's server.
 *
 * It plays the server side of the protocol: it is the Noise initiator, it
 * sends the cleartext init and handshake messages, and it encrypts
 * everything that follows.
 */
internal class TestServer(
    private val keys: TestKeyStore,
    private val transport: TestTransport,
    private val suite: NoiseSuite = NoiseSuite.AES_GCM_SHA256,
) {
    private val codec = MessageCodec()

    val serverStatic = ByteArray(32) { (it + 200).toByte() }
    val serverId = Base64Url.encode(X25519.publicKey(serverStatic))

    /** The key the server names in its first handshake message. */
    var namedPskId: String = SendspinPsk.SENTINEL_ID

    /** The category the server names. */
    var namedCategory: String = "sn"

    /**
     * The key the server names, when the category is not the Sentinel key.
     *
     * A long-term key is the one that a pairing produced.
     */
    var namedPsk: ByteArray? = null

    /** The Noise handshake, until it finishes. */
    private var handshake: Kkpsk2Handshake? = null

    /** The encrypted channel. */
    private var channel: NoiseTransport? = null

    /** The re-handshake, while one runs. */
    private var rehandshake: Kkpsk2Handshake? = null

    /**
     * How many messages the server has opened.
     *
     * The messages have to be opened in the order they arrived, because the
     * cipher counts them.
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
     * The payload of the first Noise handshake message.
     *
     * The server names the key it wants, and the category it holds it
     * under.
     */
    private fun handshakePayload(pskId: String, category: String): String =
        "{\"psk_id\":\"$pskId\",\"psk_category\":\"$category\"}"

    /**
     * Runs the handshake up to the point where the client answers.
     *
     * The prologue binds the handshake to the exact bytes of the two init
     * messages, so the server uses the text it received.
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
            "lt" -> namedPsk ?: SendspinPsk.SENTINEL
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
     * The opened messages stay in the queue, so the test can look at them in
     * any order.
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
    fun timeExchanges(): List<com.sendspin.protocol.protocol.ClientTimePayload> {
        drain()
        val out = mutableListOf<com.sendspin.protocol.protocol.ClientTimePayload>()
        val iterator = queue.iterator()
        while (iterator.hasNext()) {
            val message = iterator.next()
            if (message is DecryptedMessage.Json && message.type == MessageType.CLIENT_TIME) {
                out += codec.decode<com.sendspin.protocol.protocol.ClientTimePayload>(
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
     * A re-handshake runs inside the encrypted channel. Its prologue is the
     * hash of the previous handshake.
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

    /** The handshake hash of the current channel. */
    val handshakeHash: ByteArray
        get() = channel!!.handshakeHash
}
