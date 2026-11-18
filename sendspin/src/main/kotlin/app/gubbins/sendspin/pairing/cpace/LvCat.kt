package app.gubbins.sendspin.pairing.cpace

/**
 * The length-prefixed concatenation of the CPace draft, appendix A.2.
 *
 * The lengths are LEB128 varints, not fixed width. Getting that wrong
 * produces an implementation that agrees with itself in every test and
 * disagrees with every real peer, because a prefix error changes every
 * downstream byte of the generator, the session key and both confirmation
 * tags.
 */

/** LEB128-encodes [value] as an unsigned base-128 varint. */
private fun leb128(value: Int): ByteArray {
    require(value >= 0) { "a length cannot be negative: $value" }
    var remaining = value
    val out = ArrayList<Byte>(2)
    do {
        var byte = remaining and 0x7F
        remaining = remaining ushr 7
        if (remaining != 0) byte = byte or 0x80
        out.add(byte.toByte())
    } while (remaining != 0)
    return out.toByteArray()
}

/** The LEB128 length of [data], then the data itself. */
internal fun prependLen(data: ByteArray): ByteArray = leb128(data.size) + data

/** Every part length-prefixed, then concatenated in order. */
internal fun lvCat(vararg parts: ByteArray): ByteArray {
    var out = ByteArray(0)
    for (part in parts) out += prependLen(part)
    return out
}
