package com.sendspin.protocol.session

import com.sendspin.protocol.crypto.Aead
import com.sendspin.protocol.crypto.Base64Url
import com.sendspin.protocol.crypto.NoiseSuite
import com.sendspin.protocol.crypto.X25519
import com.sendspin.protocol.pairing.PairingCode
import com.sendspin.protocol.pairing.PairingWrap
import com.sendspin.protocol.pairing.cpace.CPaceX25519
import com.sendspin.protocol.protocol.ClientPairAuthPayload
import com.sendspin.protocol.protocol.ClientPairConfirmPayload
import com.sendspin.protocol.protocol.ClientPairFinalizePayload
import com.sendspin.protocol.protocol.ClientPairInitPayload
import com.sendspin.protocol.protocol.MessageCodec
import com.sendspin.protocol.protocol.MessageType
import com.sendspin.protocol.protocol.PairingParameters
import com.sendspin.protocol.protocol.ServerActivatePayload
import com.sendspin.protocol.protocol.ServerHelloPayload
import com.sendspin.protocol.protocol.ServerPairAuthPayload
import com.sendspin.protocol.protocol.ServerPairConfirmPayload
import com.sendspin.protocol.protocol.ServerPairFinalizePayload
import com.sendspin.protocol.protocol.ServerPairInitPayload
import com.sendspin.protocol.protocol.SourceSupport
import com.sendspin.protocol.psk.SendspinPsk
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The pairing by a dynamic code.
 *
 * The test plays the server side and the operator at once. The operator
 * reads the code off the client's state and types it into the server, which
 * is exactly what a person does with the two screens.
 */
class DynamicPairingTest {
    private val codec = MessageCodec()
    private val clock = TestClock()
    private val suite = NoiseSuite.AES_GCM_SHA256

    @Test
    fun `the client offers the code method`() {
        val (session, transport, server) = handshake()
        sayHello(session, server)
        val hello = codec.decode<com.sendspin.protocol.protocol.ClientHelloPayload>(
            MessageType.CLIENT_HELLO,
            server.nextJson(MessageType.CLIENT_HELLO),
        )
        val methods = hello.supportedPairMethods
        assertTrue(methods.containsKey("dynamic_pairing_code"))
        assertEquals(listOf("digits"), methods["dynamic_pairing_code"]?.formats)
        assertEquals(listOf("display"), methods["dynamic_pairing_code"]?.outChannels)
        assertTrue(methods.containsKey("pairing_psk"))
        assertNotNull(transport)
    }

    @Test
    fun `the session pairs with a code`() {
        val (session, transport, server) = handshake()
        sayHello(session, server)
        val attempt = startCodePairing(session, server)

        // The code appears once the server's nonce has arrived.
        val code = session.state.pairingCode
        assertNotNull(code)
        assertEquals(6, code!!.length)
        assertTrue(code.all { it.isDigit() })

        // The operator types the code into the server.
        val initiator = TestInitiator(code.toByteArray(), attempt.sid)
        send(
            session,
            server,
            MessageType.SERVER_PAIR_AUTH,
            ServerPairAuthPayload(pakeMsg1 = Base64Url.encode(initiator.publicShare)),
        )
        val auth = codec.decode<ClientPairAuthPayload>(
            MessageType.CLIENT_PAIR_AUTH,
            server.nextJson(MessageType.CLIENT_PAIR_AUTH),
        )
        assertTrue(initiator.derive(Base64Url.decode(auth.pakeMsg2)!!))

        // The server confirms, and the client answers.
        send(
            session,
            server,
            MessageType.SERVER_PAIR_CONFIRM,
            ServerPairConfirmPayload(serverKc = Base64Url.encode(initiator.tag())),
        )
        val confirm = codec.decode<ClientPairConfirmPayload>(
            MessageType.CLIENT_PAIR_CONFIRM,
            server.nextJson(MessageType.CLIENT_PAIR_CONFIRM),
        )
        assertTrue(initiator.verify(Base64Url.decode(confirm.clientKc)!!))

        // The opening of the client's nonce travels under the key that the
        // exchange produced, and it opens to the nonce that the client
        // committed to.
        val nonceB = Aead.open(
            suite,
            PairingWrap.wrapKey(PairingWrap.NONCE_LABEL, attempt.sid, initiator.isk),
            ByteArray(Aead.NONCE_SIZE),
            ByteArray(0),
            Base64Url.decode(confirm.wrappedNonceB!!)!!,
        )
        assertEquals(32, nonceB.size)
        assertArrayEquals(PairingCode.commit(nonceB), Base64Url.decode(attempt.commitB)!!)

        // The long-term key travels the same way.
        val finalize = codec.decode<ClientPairFinalizePayload>(
            MessageType.CLIENT_PAIR_FINALIZE,
            server.nextJson(MessageType.CLIENT_PAIR_FINALIZE),
        )
        assertNull(finalize.longTermPsk)
        val psk = Aead.open(
            suite,
            PairingWrap.wrapKey(PairingWrap.PSK_LABEL, attempt.sid, initiator.isk),
            ByteArray(Aead.NONCE_SIZE),
            ByteArray(0),
            Base64Url.decode(finalize.wrappedPsk!!)!!,
        )
        assertEquals(32, psk.size)

        // The server confirms, and the client stores its side.
        send(session, server, MessageType.SERVER_PAIR_FINALIZE, ServerPairFinalizePayload())
        assertTrue(session.state.paired)
        assertNull(session.state.pairingCode)
        assertFalse(session.state.pairingInProgress)
        assertNotNull(transport)
    }

    @Test
    fun `a wrong code asks for another round and keeps the code`() {
        val (session, transport, server) = handshake()
        sayHello(session, server)
        val attempt = startCodePairing(session, server)
        val code = session.state.pairingCode!!
        val initiator = TestInitiator(code.toByteArray(), attempt.sid)

        send(
            session,
            server,
            MessageType.SERVER_PAIR_AUTH,
            ServerPairAuthPayload(pakeMsg1 = Base64Url.encode(initiator.publicShare)),
        )
        val auth = codec.decode<ClientPairAuthPayload>(
            MessageType.CLIENT_PAIR_AUTH,
            server.nextJson(MessageType.CLIENT_PAIR_AUTH),
        )
        assertTrue(initiator.derive(Base64Url.decode(auth.pakeMsg2)!!))

        // The server sends a tag that does not verify.
        send(
            session,
            server,
            MessageType.SERVER_PAIR_CONFIRM,
            ServerPairConfirmPayload(serverKc = Base64Url.encode(ByteArray(64))),
        )
        server.nextJson(MessageType.CLIENT_PAIR_RETRY)
        assertEquals(code, session.state.pairingCode)
        assertTrue(session.state.pairingInProgress)
        assertNotNull(transport)
    }


    /** What the server needs to join the exchange. */
    private class Attempt(
        val pairingIndex: Int,
        val commitB: String,
        val sid: ByteArray,
    )

    /**
     * Starts the code pairing on the server.
     *
     * @return what the server needs to take part
     */
    private fun startCodePairing(
        session: SourceSession,
        server: TestServer,
    ): Attempt {
        send(
            session,
            server,
            MessageType.SERVER_ACTIVATE,
            ServerActivatePayload(
                activities = listOf("pairing"),
                activeRoles = listOf(),
                pairing = PairingParameters(method = "dynamic_pairing_code", format = "digits"),
            ),
        )
        val init = codec.decode<ClientPairInitPayload>(
            MessageType.CLIENT_PAIR_INIT,
            server.nextJson(MessageType.CLIENT_PAIR_INIT),
        )
        assertNotNull(init.commitB)
        val nonceA = ByteArray(32) { (it + 7).toByte() }
        send(
            session,
            server,
            MessageType.SERVER_PAIR_INIT,
            ServerPairInitPayload(nonceA = Base64Url.encode(nonceA)),
        )
        val sid = PairingWrap.sessionId(server.handshakeHash, init.pairingIndex, 1)
        return Attempt(init.pairingIndex, init.commitB!!, sid)
    }

    /** Runs the handshake, and leaves the session ready for the hello. */
    private fun handshake(
        keys: TestKeyStore = TestKeyStore(),
    ): Triple<SourceSession, TestTransport, TestServer> {
        val transport = TestTransport()
        val session = SourceSession(
            keys = keys,
            transport = transport,
            clock = clock,
            listener = TestListener(),
            configuration = SourceSessionConfiguration(
                clientName = "Test Phone",
                suite = suite,
            ),
        )
        val server = TestServer(keys, transport, suite)
        session.start()
        server.startHandshake(transport.texts.last())
        session.onTextMessage(server.serverInitText)
        session.onTextMessage(server.firstHandshakeMessage())
        server.finishHandshake(transport.texts.last())
        return Triple(session, transport, server)
    }

    /** Sends a JSON message to the client. */
    private fun send(session: SourceSession, server: TestServer, type: String, payload: Any) {
        session.onBinaryMessage(server.encryptJson(type, payload))
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
     * The server side of a CPace run.
     *
     * The client is always the responder, so the test plays the initiator.
     */
    private class TestInitiator(
        prs: ByteArray,
        private val sid: ByteArray,
    ) {
        private val scalar = X25519.generatePrivateKey()
        private val generator = CPaceX25519.calculateGenerator(prs, ByteArray(0), sid)

        /** The server's share, which is sent as `pake_msg_1`. */
        val publicShare: ByteArray = CPaceX25519.scalarMultVfy(scalar, generator)!!

        /** The session key, once it has been derived. */
        var isk: ByteArray = ByteArray(0)
            private set

        private var peerShare: ByteArray? = null
        private var macKey: ByteArray? = null

        /** Takes in the client's share. */
        fun derive(peerShare: ByteArray): Boolean {
            val shared = CPaceX25519.scalarMultVfy(scalar, peerShare) ?: return false
            this.peerShare = peerShare
            isk = CPaceX25519.deriveIsk(
                sid = sid,
                shared = shared,
                ya = publicShare,
                ada = AD_SERVER,
                yb = peerShare,
                adb = AD_CLIENT,
            )
            macKey = CPaceX25519.macKey(sid, isk)
            return true
        }

        /** The server's confirmation tag. */
        fun tag(): ByteArray =
            CPaceX25519.mcfTag(macKey!!, publicShare, AD_SERVER)

        /** Checks the client's confirmation tag. */
        fun verify(clientKc: ByteArray): Boolean {
            val peer = peerShare ?: return false
            val expected = CPaceX25519.mcfTag(macKey!!, peer, AD_CLIENT)
            if (expected.size != clientKc.size) return false
            var difference = 0
            for (index in expected.indices) {
                difference = difference or (expected[index].toInt() xor clientKc[index].toInt())
            }
            return difference == 0
        }

        private companion object {
            val AD_SERVER = "server".toByteArray(Charsets.UTF_8)
            val AD_CLIENT = "client".toByteArray(Charsets.UTF_8)
        }
    }
}
