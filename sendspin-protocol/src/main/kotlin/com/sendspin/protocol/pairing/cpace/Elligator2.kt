package com.sendspin.protocol.pairing.cpace

import java.math.BigInteger

/**
 * The Elligator2 map of the CPace draft, for the group G_X25519.
 *
 * The draft keeps only the x-coordinate, so the map never computes the
 * y-coordinate. That leaves the square test that picks between the two
 * candidate x values, and nothing else of the y-coordinate machinery.
 *
 * The arithmetic uses `BigInteger`. The field is 255 bits wide, so every
 * operation is small, and the pairing code needs one map per round.
 *
 * A timing note. The square test is a variable-time exponentiation. That is
 * acceptable here only because the dynamic pairing code is not a long-term
 * secret. It sits on the device screen for the length of one attempt, it is
 * used only in that attempt, and each round runs under its own session id.
 * Do not carry this reasoning over to the pairing key, whose secret lasts
 * as long as the device.
 */
public object Elligator2 {
    /** The prime of the curve25519 field. */
    private val Q: BigInteger = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19))

    /** The curve constant A. The constant B is one, and it folds into the arithmetic. */
    private val A: BigInteger = BigInteger.valueOf(486662)

    /** The non-square that the map uses. */
    private val Z: BigInteger = BigInteger.valueOf(2)

    /** The inverse of two. */
    private val HALF: BigInteger = BigInteger.TWO.modInverse(Q)

    /** The exponent that tells a square from a non-square. */
    private val LEGENDRE: BigInteger = Q.subtract(BigInteger.ONE).divide(BigInteger.TWO)

    /**
     * Maps a field element to a point on the curve.
     *
     * @param u the 32-byte little-endian field element, with the top bit
     *   already cleared
     * @return the 32-byte little-endian x-coordinate of the mapped point
     */
    public fun map(u: ByteArray): ByteArray {
        require(u.size == FIELD_SIZE) { "a field element is $FIELD_SIZE bytes, got ${u.size}" }
        val r = BigInteger(1, u.reversedArray()).mod(Q)
        val tv1 = Z.multiply(r).multiply(r).mod(Q)
        val v = A.negate().multiply(inv0(BigInteger.ONE.add(tv1).mod(Q))).mod(Q)
        val gx1 = v.multiply(v).multiply(v)
            .add(A.multiply(v).multiply(v))
            .add(v)
            .mod(Q)
        // The symbol is one for a square, and one less than the prime for
        // anything else. A zero stays zero.
        val symbol = gx1.modPow(LEGENDRE, Q)
        val x = symbol.multiply(v)
            .subtract(BigInteger.ONE.subtract(symbol).multiply(A).multiply(HALF))
            .mod(Q)
        return encodeLittleEndian(x)
    }

    /**
     * The inverse of [value], with zero mapped to zero.
     *
     * The draft calls this `inv0`. Fermat's little theorem gives the same
     * answer as the extended Euclidean algorithm, and the zero case needs
     * its own rule because no inverse of zero exists.
     */
    private fun inv0(value: BigInteger): BigInteger =
        if (value.signum() == 0) BigInteger.ZERO else value.modInverse(Q)

    /** The 32-byte little-endian form of a field element. */
    private fun encodeLittleEndian(value: BigInteger): ByteArray {
        val big = value.toByteArray()
        val out = ByteArray(FIELD_SIZE)
        for (index in 0 until FIELD_SIZE) {
            val source = big.size - 1 - index
            out[index] = if (source >= 0) big[source] else 0
        }
        return out
    }

    /** The size of a field element, in bytes. */
    public const val FIELD_SIZE: Int = 32
}
