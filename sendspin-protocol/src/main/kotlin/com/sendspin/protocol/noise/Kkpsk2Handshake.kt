package com.sendspin.protocol.noise

import com.sendspin.protocol.crypto.NoiseSuite
import com.sendspin.protocol.crypto.X25519

/**
 * The `KKpsk2` handshake that SendSpin uses, on both sides.
 *
 * The pattern is the standard Noise `KKpsk2`, but two details differ from
 * plain Noise, and both come from the specification.
 *
 * First, the PSK is chosen late. The server names a PSK in the first
 * handshake message, and the client picks the matching PSK from its store
 * before it processes the second message. The server therefore reads the
 * first message without a PSK, and the client supplies a PSK that was not
 * known when the handshake began.
 *
 * Second, the prologue. The specification binds the handshake to the
 * `client/init` and `server/init` messages, by hashing their raw bytes. A
 * re-handshake uses the previous handshake hash as its prologue instead.
 *
 * The two static keys are known to both parties in advance, which is what
 * the `KK` part of the pattern name means.
 */
public class Kkpsk2Handshake private constructor(
    private val suite: NoiseSuite,
    private var state: SymmetricState,
    private val localStaticPrivate: ByteArray,
    private val remoteStaticPublic: ByteArray,
    private val ephemeralPrivate: ByteArray,
    private val ephemeralPublic: ByteArray,
    private val isInitiator: Boolean,
) {
    /** The initiator's ephemeral public key, once it has been read. */
    private var remoteEphemeralPublic: ByteArray? = null

    /** The state as it was before the second message, for the PSK retry. */
    private var stateBeforeMessage2: SymmetricState? = null

    public companion object {
        /**
         * Starts a handshake as the initiator, which is the server side.
         *
         * @param suite the cipher suite
         * @param prologue the bytes to hash into the handshake
         * @param localStaticPrivate the server's static private key
         * @param remoteStaticPublic the client's static public key
         * @param ephemeralPrivate the ephemeral private key to use, or null
         *   to generate one
         */
        public fun initiator(
            suite: NoiseSuite,
            prologue: ByteArray,
            localStaticPrivate: ByteArray,
            remoteStaticPublic: ByteArray,
            ephemeralPrivate: ByteArray? = null,
        ): Kkpsk2Handshake {
            val ephemeral = ephemeralPrivate ?: X25519.generatePrivateKey()
            val localStaticPublic = X25519.publicKey(localStaticPrivate)
            return Kkpsk2Handshake(
                suite = suite,
                state = SymmetricState(suite, protocolName(suite)).apply {
                    mixHash(prologue)
                    // The pre-message. Both sides hash the initiator's
                    // static key first, then the responder's.
                    mixHash(localStaticPublic)
                    mixHash(remoteStaticPublic)
                },
                localStaticPrivate = localStaticPrivate,
                remoteStaticPublic = remoteStaticPublic,
                ephemeralPrivate = ephemeral,
                ephemeralPublic = X25519.publicKey(ephemeral),
                isInitiator = true,
            )
        }

        /**
         * Starts a handshake as the responder, which is the client side.
         *
         * @param suite the cipher suite
         * @param prologue the bytes to hash into the handshake
         * @param localStaticPrivate the client's static private key
         * @param remoteStaticPublic the server's static public key
         * @param ephemeralPrivate the ephemeral private key to use, or null
         *   to generate one
         */
        public fun responder(
            suite: NoiseSuite,
            prologue: ByteArray,
            localStaticPrivate: ByteArray,
            remoteStaticPublic: ByteArray,
            ephemeralPrivate: ByteArray? = null,
        ): Kkpsk2Handshake {
            val ephemeral = ephemeralPrivate ?: X25519.generatePrivateKey()
            return Kkpsk2Handshake(
                suite = suite,
                state = SymmetricState(suite, protocolName(suite)).apply {
                    mixHash(prologue)
                    // The pre-message, in the same order as on the
                    // initiator's side.
                    mixHash(remoteStaticPublic)
                    mixHash(X25519.publicKey(localStaticPrivate))
                },
                localStaticPrivate = localStaticPrivate,
                remoteStaticPublic = remoteStaticPublic,
                ephemeralPrivate = ephemeral,
                ephemeralPublic = X25519.publicKey(ephemeral),
                isInitiator = false,
            )
        }

        /** The Noise protocol name for a suite. */
        public fun protocolName(suite: NoiseSuite): String =
            "Noise_KKpsk2_${suite.protocolName}"
    }

    /**
     * Writes the first handshake message.
     *
     * The payload is the JSON object that the specification defines for this
     * message. On the server side it carries the PSK identifier.
     */
    public fun writeMessage1(payload: ByteArray): ByteArray {
        check(isInitiator) { "Only the initiator writes the first message" }
        // The "e" token mixes the ephemeral public key into the hash, and
        // then into the chaining key, because this is a PSK handshake.
        state.mixHash(ephemeralPublic)
        state.mixKey(ephemeralPublic)
        // The "es" token: the ephemeral key with the responder's static key.
        state.mixKey(dh(ephemeralPrivate, remoteStaticPublic))
        // The "ss" token: the two static keys.
        state.mixKey(dh(localStaticPrivate, remoteStaticPublic))
        val ciphertext = state.encryptAndHash(payload)
        stateBeforeMessage2 = state.copy()
        return ephemeralPublic + ciphertext
    }

    /**
     * Reads the first handshake message and returns its payload.
     *
     * The responder calls this before it knows which PSK to use. The payload
     * names the PSK, and the caller looks it up.
     */
    public fun readMessage1(message: ByteArray): ByteArray {
        check(!isInitiator) { "Only the responder reads the first message" }
        val remoteEphemeral = message.copyOfRange(0, X25519.KEY_SIZE)
        remoteEphemeralPublic = remoteEphemeral
        state.mixHash(remoteEphemeral)
        state.mixKey(remoteEphemeral)
        state.mixKey(dh(localStaticPrivate, remoteEphemeral))
        state.mixKey(dh(localStaticPrivate, remoteStaticPublic))
        return state.decryptAndHash(message.copyOfRange(X25519.KEY_SIZE, message.size))
    }

    /**
     * Writes the second handshake message with the given PSK.
     *
     * The responder calls this once it has looked up the PSK named in the
     * first message.
     */
    public fun writeMessage2(payload: ByteArray, psk: ByteArray): ByteArray {
        check(!isInitiator) { "Only the responder writes the second message" }
        val remoteEphemeral = checkNotNull(remoteEphemeralPublic) {
            "The first handshake message must be read first"
        }
        state.mixHash(ephemeralPublic)
        state.mixKey(ephemeralPublic)
        state.mixKey(dh(ephemeralPrivate, remoteEphemeral))
        state.mixKey(dh(ephemeralPrivate, remoteStaticPublic))
        // The "psk" token mixes the pre-shared key in and hashes it.
        state.mixKeyAndHash(psk)
        val ciphertext = state.encryptAndHash(payload)
        return ephemeralPublic + ciphertext
    }

    /**
     * Reads the second handshake message and returns its payload.
     *
     * The initiator calls this with the PSK it named in the first message.
     */
    public fun readMessage2(message: ByteArray, psk: ByteArray): ByteArray {
        check(isInitiator) { "Only the initiator reads the second message" }
        val remoteEphemeral = message.copyOfRange(0, X25519.KEY_SIZE)
        state.mixHash(remoteEphemeral)
        state.mixKey(remoteEphemeral)
        state.mixKey(dh(ephemeralPrivate, remoteEphemeral))
        state.mixKey(dh(localStaticPrivate, remoteEphemeral))
        // The "psk" token.
        state.mixKeyAndHash(psk)
        return state.decryptAndHash(message.copyOfRange(X25519.KEY_SIZE, message.size))
    }

    /**
     * Reads the second handshake message, trying each PSK in turn.
     *
     * The first PSK is the one the server named. The second is the Sentinel
     * PSK, which the client falls back to when it does not hold the named
     * one. Each attempt starts from the state as it was before the first
     * one, because a failed read changes the state.
     *
     * @return the payload and the PSK that worked
     */
    public fun tryReadMessage2(message: ByteArray, psks: List<ByteArray>): Message2Result {
        check(isInitiator) { "Only the initiator reads the second message" }
        val saved = checkNotNull(stateBeforeMessage2) {
            "The first handshake message must be written first"
        }
        for (psk in psks) {
            state = saved.copy()
            try {
                return Message2Result(readMessage2(message, psk), psk)
            } catch (_: Exception) {
                // Try the next PSK.
            }
        }
        throw IllegalStateException("The second handshake message did not decrypt")
    }

    /**
     * The transport keys that follow a completed handshake.
     *
     * Call this after the second handshake message has been read or written.
     */
    public fun split(): NoiseTransport {
        val (first, second) = state.split()
        return if (isInitiator) {
            NoiseTransport(send = first, receive = second, handshakeHash = state.handshakeHash)
        } else {
            NoiseTransport(send = second, receive = first, handshakeHash = state.handshakeHash)
        }
    }

    /** The handshake hash, which a re-handshake uses as its prologue. */
    public val handshakeHash: ByteArray
        get() = state.handshakeHash

    private fun dh(privateKey: ByteArray, publicKey: ByteArray): ByteArray =
        X25519.sharedSecret(privateKey, publicKey)

    /** The result of reading the second handshake message. */
    public data class Message2Result(
        /** The payload of the message. */
        val payload: ByteArray,
        /** The PSK that decrypted it. */
        val psk: ByteArray,
    )
}
