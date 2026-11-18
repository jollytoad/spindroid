package app.gubbins.sendspin.pairing.cpace

import app.gubbins.sendspin.crypto.Hashing
import app.gubbins.sendspin.crypto.X25519

/**
 * The CPace-X25519-SHA512 primitives of the CPace draft.
 *
 * The responder side only. SendSpin makes the server the initiator and this
 * client the responder, so nothing here computes an initiator value.
 */
public object CPaceX25519 {
    /** The domain separation label of the group and the generator string. */
    public const val DSI: String = "CPace255"

    /** The domain separation label of the session key. */
    public const val DSI_ISK: String = "CPace255_ISK"

    /** The label of the confirmation MAC key. */
    public const val MAC_LABEL: String = "CPaceMac"

    /** The SHA-512 input block size, which sizes the zero padding. */
    private const val BLOCK_SIZE: Int = 128

    /** The size of a share, a session key input and a field element. */
    public const val SIZE: Int = 32

    /**
     * The generator string of appendix A.2.
     *
     * The padding length uses the length-prefixed sizes of the label and the
     * password, not their raw sizes. That is the easy thing to get wrong,
     * and it changes every downstream byte.
     */
    public fun generatorString(prs: ByteArray, ci: ByteArray, sid: ByteArray): ByteArray {
        val label = DSI.toByteArray(Charsets.UTF_8)
        val padding = maxOf(
            0,
            BLOCK_SIZE - prependLen(prs).size - prependLen(label).size - 1,
        )
        return lvCat(label, prs, ByteArray(padding), ci, sid)
    }

    /**
     * The generator point of the group.
     *
     * The hash is read as a field element, with the top bit cleared, and
     * then mapped to the curve.
     */
    public fun calculateGenerator(prs: ByteArray, ci: ByteArray, sid: ByteArray): ByteArray {
        val u = Hashing.sha512(generatorString(prs, ci, sid)).copyOf(SIZE)
        // RFC 7748 reads a 255-bit field element, so the top bit is dropped.
        u[SIZE - 1] = (u[SIZE - 1].toInt() and 0x7F).toByte()
        return Elligator2.map(u)
    }

    /**
     * The scalar multiplication, with the check that the draft requires.
     *
     * X25519 maps every low-order point, on the curve and on the twist, to
     * the all-zero string, so the check reduces to a test on the result. A
     * null return means the point must be refused. It is not a retry: a
     * peer that steers both sides to a known shared secret is the attack
     * this prevents, so the failure must not be representable as a valid
     * secret.
     */
    public fun scalarMultVfy(scalar: ByteArray, point: ByteArray): ByteArray? {
        require(scalar.size == SIZE) { "the scalar is $SIZE bytes, got ${scalar.size}" }
        if (point.size != SIZE) return null
        val result = try {
            X25519.sharedSecret(scalar, point)
        } catch (_: java.security.GeneralSecurityException) {
            // The platform refuses a low-order point outright.
            return null
        } catch (_: IllegalStateException) {
            return null
        }
        var accumulator = 0
        for (byte in result) accumulator = accumulator or (byte.toInt() and 0xFF)
        return if (accumulator == 0) null else result
    }

    /**
     * The transcript, with the initiator's values first.
     *
     * The order is part of the binding, not a formatting choice. Swapping
     * the two sides gives a different session key, and that is what stops a
     * reflection attack.
     */
    public fun transcript(ya: ByteArray, ada: ByteArray, yb: ByteArray, adb: ByteArray): ByteArray =
        lvCat(ya, ada) + lvCat(yb, adb)

    /**
     * The intermediate session key of section 7.2.3.
     *
     * The three parts are hashed one after the other, without a length
     * prefix between them.
     */
    public fun deriveIsk(
        sid: ByteArray,
        shared: ByteArray,
        ya: ByteArray,
        ada: ByteArray,
        yb: ByteArray,
        adb: ByteArray,
    ): ByteArray = Hashing.sha512(
        lvCat(DSI_ISK.toByteArray(Charsets.UTF_8), sid, shared) + transcript(ya, ada, yb, adb),
    )

    /** The key that the confirmation tags use. */
    public fun macKey(sid: ByteArray, isk: ByteArray): ByteArray =
        Hashing.sha512(MAC_LABEL.toByteArray(Charsets.UTF_8) + sid + isk)

    /**
     * A confirmation tag.
     *
     * The draft leaves the MAC open and SendSpin pins it to HMAC-SHA-512.
     */
    public fun mcfTag(macKey: ByteArray, share: ByteArray, ad: ByteArray): ByteArray =
        Hashing.hmacSha512(macKey, lvCat(share, ad))
}
