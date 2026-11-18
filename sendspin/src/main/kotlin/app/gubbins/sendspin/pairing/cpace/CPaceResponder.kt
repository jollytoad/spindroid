package app.gubbins.sendspin.pairing.cpace

import app.gubbins.sendspin.crypto.X25519

/**
 * One side of a CPace run, as the responder.
 *
 * SendSpin makes the server the initiator, so this client is always the
 * responder. The channel identifier is empty, the initiator's associated data
 * is "server" and the responder's is "client".
 *
 * @param prs the password-related string, which is the pairing code
 * @param sid the session id, which binds the run to the handshake, the
 *   attempt and the round
 * @param scalar the private scalar. Production callers leave it out and get
 *   a fresh one. The tests pass a fixed one so the public share is
 *   reproducible.
 */
public class CPaceResponder(
    private val prs: ByteArray,
    private val sid: ByteArray,
    scalar: ByteArray? = null,
) {
    /** The private scalar of this side. */
    private val yb: ByteArray = scalar ?: X25519.generatePrivateKey()

    /** The initiator's share, once it has arrived. */
    private var peerShare: ByteArray? = null

    /** The session key, once it has been derived. */
    private var sessionKey: ByteArray? = null

    /** The key that the confirmation tags use. */
    private var macKey: ByteArray? = null

    /**
     * The public share of this side, which is sent as `pake_msg_2`.
     *
     * It is the generator point multiplied with the private scalar.
     */
    public val publicShare: ByteArray by lazy {
        val generator = CPaceX25519.calculateGenerator(prs, EMPTY_CI, sid)
        CPaceX25519.scalarMultVfy(yb, generator)
            ?: throw IllegalStateException("The generator is a low-order point")
    }

    /**
     * The intermediate session key.
     *
     * It is only valid after [derive].
     */
    public val isk: ByteArray
        get() = sessionKey ?: throw IllegalStateException("derive has not been called")

    /**
     * Takes in the initiator's share.
     *
     * @return false when the share cannot be used. That is a protocol
     *   error, never a retry.
     */
    public fun derive(peerShare: ByteArray): Boolean {
        val shared = CPaceX25519.scalarMultVfy(yb, peerShare) ?: return false
        this.peerShare = peerShare.copyOf()
        val derived = CPaceX25519.deriveIsk(
            sid = sid,
            shared = shared,
            ya = peerShare,
            ada = AD_SERVER,
            yb = publicShare,
            adb = AD_CLIENT,
        )
        sessionKey = derived
        macKey = CPaceX25519.macKey(sid, derived)
        return true
    }

    /**
     * Checks the initiator's confirmation tag.
     *
     * The comparison takes the same time for every input, so a wrong code
     * leaks nothing about how wrong it was.
     */
    public fun verify(serverKc: ByteArray): Boolean {
        val key = macKey ?: throw IllegalStateException("derive has not been called")
        val ya = peerShare ?: throw IllegalStateException("derive has not been called")
        val expected = CPaceX25519.mcfTag(key, ya, AD_SERVER)
        if (expected.size != serverKc.size) return false
        var difference = 0
        for (index in expected.indices) {
            difference = difference or (expected[index].toInt() xor serverKc[index].toInt())
        }
        return difference == 0
    }

    /**
     * The confirmation tag of this side, which is sent as `client_kc`.
     */
    public fun tag(): ByteArray {
        val key = macKey ?: throw IllegalStateException("derive has not been called")
        return CPaceX25519.mcfTag(key, publicShare, AD_CLIENT)
    }

    private companion object {
        /** The channel identifier is empty. */
        val EMPTY_CI = ByteArray(0)

        /** The associated data of the initiator. */
        val AD_SERVER = "server".toByteArray(Charsets.UTF_8)

        /** The associated data of the responder. */
        val AD_CLIENT = "client".toByteArray(Charsets.UTF_8)
    }
}
