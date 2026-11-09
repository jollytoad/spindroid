package com.sendspin.protocol.noise

import com.sendspin.protocol.crypto.CipherSuiteBenchmark
import com.sendspin.protocol.crypto.NoiseSuite
import com.sendspin.protocol.crypto.X25519
import com.sendspin.protocol.psk.SendspinPsk
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The handshake outside the fixed test vectors.
 */
class Kkpsk2HandshakeTest {
    private val suite = NoiseSuite.CHACHA_POLY_SHA256

    private fun keys(): Triple<ByteArray, ByteArray, ByteArray> {
        val serverStatic = X25519.generatePrivateKey()
        val clientStatic = X25519.generatePrivateKey()
        return Triple(
            serverStatic,
            clientStatic,
            X25519.publicKey(clientStatic),
        )
    }

    @Test
    fun `both sides reach the same handshake hash`() {
        val (serverStatic, clientStatic, clientPublic) = keys()
        val prologue = "prologue".toByteArray()

        val server = Kkpsk2Handshake.initiator(
            suite = suite,
            prologue = prologue,
            localStaticPrivate = serverStatic,
            remoteStaticPublic = clientPublic,
        )
        val client = Kkpsk2Handshake.responder(
            suite = suite,
            prologue = prologue,
            localStaticPrivate = clientStatic,
            remoteStaticPublic = X25519.publicKey(serverStatic),
        )

        val message1 = server.writeMessage1("first".toByteArray())
        assertArrayEquals("first".toByteArray(), client.readMessage1(message1))

        val message2 = client.writeMessage2("second".toByteArray(), SendspinPsk.SENTINEL)
        assertArrayEquals("second".toByteArray(), server.readMessage2(message2, SendspinPsk.SENTINEL))

        assertArrayEquals(server.handshakeHash, client.handshakeHash)
    }

    @Test
    fun `the transport carries messages in both directions`() {
        val (serverStatic, clientStatic, clientPublic) = keys()
        val prologue = "prologue".toByteArray()

        val server = Kkpsk2Handshake.initiator(
            suite = suite,
            prologue = prologue,
            localStaticPrivate = serverStatic,
            remoteStaticPublic = clientPublic,
        )
        val client = Kkpsk2Handshake.responder(
            suite = suite,
            prologue = prologue,
            localStaticPrivate = clientStatic,
            remoteStaticPublic = X25519.publicKey(serverStatic),
        )

        val message1 = server.writeMessage1(ByteArray(0))
        client.readMessage1(message1)
        val message2 = client.writeMessage2(ByteArray(0), SendspinPsk.SENTINEL)
        server.readMessage2(message2, SendspinPsk.SENTINEL)

        val serverTransport = server.split()
        val clientTransport = client.split()

        val toClient = serverTransport.encrypt("to the client".toByteArray())
        assertArrayEquals("to the client".toByteArray(), clientTransport.decrypt(toClient))

        val toServer = clientTransport.encrypt("to the server".toByteArray())
        assertArrayEquals("to the server".toByteArray(), serverTransport.decrypt(toServer))
    }

    @Test
    fun `the prologue changes the handshake`() {
        val (serverStatic, clientStatic, clientPublic) = keys()

        val first = Kkpsk2Handshake.initiator(
            suite = suite,
            prologue = "one".toByteArray(),
            localStaticPrivate = serverStatic,
            remoteStaticPublic = clientPublic,
        )
        val second = Kkpsk2Handshake.initiator(
            suite = suite,
            prologue = "two".toByteArray(),
            localStaticPrivate = serverStatic,
            remoteStaticPublic = clientPublic,
        )

        assertNotEquals(
            first.writeMessage1(ByteArray(0)).toList(),
            second.writeMessage1(ByteArray(0)).toList(),
        )
    }

    @Test
    fun `a re-handshake uses the previous handshake hash as its prologue`() {
        val (serverStatic, clientStatic, clientPublic) = keys()
        val prologue = "prologue".toByteArray()

        val server = Kkpsk2Handshake.initiator(
            suite = suite,
            prologue = prologue,
            localStaticPrivate = serverStatic,
            remoteStaticPublic = clientPublic,
        )
        val client = Kkpsk2Handshake.responder(
            suite = suite,
            prologue = prologue,
            localStaticPrivate = clientStatic,
            remoteStaticPublic = X25519.publicKey(serverStatic),
        )

        val message1 = server.writeMessage1(ByteArray(0))
        client.readMessage1(message1)
        val message2 = client.writeMessage2(ByteArray(0), SendspinPsk.SENTINEL)
        server.readMessage2(message2, SendspinPsk.SENTINEL)

        val firstTransport = server.split()

        // The second handshake starts with the previous handshake hash.
        val serverAgain = Kkpsk2Handshake.initiator(
            suite = suite,
            prologue = firstTransport.handshakeHash,
            localStaticPrivate = serverStatic,
            remoteStaticPublic = clientPublic,
        )
        val clientAgain = Kkpsk2Handshake.responder(
            suite = suite,
            prologue = firstTransport.handshakeHash,
            localStaticPrivate = clientStatic,
            remoteStaticPublic = X25519.publicKey(serverStatic),
        )

        val message1Again = serverAgain.writeMessage1(ByteArray(0))
        clientAgain.readMessage1(message1Again)
        val message2Again = clientAgain.writeMessage2(ByteArray(0), SendspinPsk.SENTINEL)
        serverAgain.readMessage2(message2Again, SendspinPsk.SENTINEL)

        // The two sessions have different keys, because the prologue
        // changed.
        assertNotEquals(
            firstTransport.handshakeHash.toList(),
            serverAgain.handshakeHash.toList(),
        )
        assertArrayEquals(serverAgain.handshakeHash, clientAgain.handshakeHash)
    }

    @Test
    fun `the client falls back to the sentinel psk`() {
        val (serverStatic, clientStatic, clientPublic) = keys()
        val prologue = "prologue".toByteArray()
        val unknownPsk = ByteArray(32) { 0x42 }

        val server = Kkpsk2Handshake.initiator(
            suite = suite,
            prologue = prologue,
            localStaticPrivate = serverStatic,
            remoteStaticPublic = clientPublic,
        )
        val client = Kkpsk2Handshake.responder(
            suite = suite,
            prologue = prologue,
            localStaticPrivate = clientStatic,
            remoteStaticPublic = X25519.publicKey(serverStatic),
        )

        val message1 = server.writeMessage1(ByteArray(0))
        client.readMessage1(message1)

        // The server did not hold the key the client named, so it fell
        // back to the sentinel key itself.
        val message2 = client.writeMessage2(ByteArray(0), SendspinPsk.SENTINEL)

        // The server tries the key it named first, then the sentinel.
        val result = server.tryReadMessage2(message2, listOf(unknownPsk, SendspinPsk.SENTINEL))

        assertArrayEquals(SendspinPsk.SENTINEL, result.psk)
        assertArrayEquals(ByteArray(0), result.payload)
    }

    @Test
    fun `a handshake fails when no psk matches`() {
        val (serverStatic, clientStatic, clientPublic) = keys()
        val prologue = "prologue".toByteArray()

        val server = Kkpsk2Handshake.initiator(
            suite = suite,
            prologue = prologue,
            localStaticPrivate = serverStatic,
            remoteStaticPublic = clientPublic,
        )
        val client = Kkpsk2Handshake.responder(
            suite = suite,
            prologue = prologue,
            localStaticPrivate = clientStatic,
            remoteStaticPublic = X25519.publicKey(serverStatic),
        )

        val message1 = server.writeMessage1(ByteArray(0))
        client.readMessage1(message1)
        val message2 = client.writeMessage2(ByteArray(0), ByteArray(32) { 0x42 })

        assertThrows(IllegalStateException::class.java) {
            server.tryReadMessage2(message2, listOf(ByteArray(32) { 0x11 }))
        }
    }

    @Test
    fun `the roles refuse the wrong direction`() {
        val (serverStatic, clientStatic, clientPublic) = keys()
        val server = Kkpsk2Handshake.initiator(
            suite = suite,
            prologue = ByteArray(0),
            localStaticPrivate = serverStatic,
            remoteStaticPublic = clientPublic,
        )
        val client = Kkpsk2Handshake.responder(
            suite = suite,
            prologue = ByteArray(0),
            localStaticPrivate = clientStatic,
            remoteStaticPublic = X25519.publicKey(serverStatic),
        )

        // The initiator writes the first message and reads the second. The
        // responder does the opposite.
        assertThrows(IllegalStateException::class.java) {
            server.readMessage1(ByteArray(64))
        }
        assertThrows(IllegalStateException::class.java) {
            client.writeMessage1(ByteArray(0))
        }
        assertThrows(IllegalStateException::class.java) {
            client.readMessage2(ByteArray(64), ByteArray(32))
        }
        assertThrows(IllegalStateException::class.java) {
            server.writeMessage2(ByteArray(0), ByteArray(32))
        }
    }

    @Test
    fun `the transport refuses an oversized message`() {
        val transport = NoiseTransport(
            send = CipherState(suite, ByteArray(32)),
            receive = CipherState(suite, ByteArray(32)),
            handshakeHash = ByteArray(32),
        )
        assertThrows(IllegalArgumentException::class.java) {
            transport.encrypt(ByteArray(NoiseTransport.MAX_PLAINTEXT_SIZE + 1))
        }
    }
}

/**
 * The choice of cipher suite.
 */
class CipherSuiteBenchmarkTest {
    @Test
    fun `the measurement gives a positive time`() {
        val elapsed = CipherSuiteBenchmark.nanosecondsPerOperation
        assertTrue(elapsed > 0, "The measurement gave $elapsed nanoseconds")
    }

    @Test
    fun `the measurement runs once per process`() {
        val first = CipherSuiteBenchmark.nanosecondsPerOperation
        val second = CipherSuiteBenchmark.nanosecondsPerOperation
        assertEquals(first, second)
    }

    @Test
    fun `the preferred suite is one of the two`() {
        val suite = CipherSuiteBenchmark.preferredSuite()
        assertTrue(
            suite == NoiseSuite.AES_GCM_SHA256 || suite == NoiseSuite.CHACHA_POLY_SHA256,
            "The preferred suite is $suite",
        )
        assertEquals(
            suite == NoiseSuite.AES_GCM_SHA256,
            CipherSuiteBenchmark.isAesFastEnough(),
        )
    }

    @Test
    fun `the threshold leaves room for the audio rate`() {
        // A source sends about fifty chunks a second. The threshold must
        // keep the cipher well under the time between two chunks.
        assertTrue(
            CipherSuiteBenchmark.THRESHOLD_NANOS < 1_000_000_000L / 50,
            "The threshold is ${CipherSuiteBenchmark.THRESHOLD_NANOS} nanoseconds",
        )
    }
}
