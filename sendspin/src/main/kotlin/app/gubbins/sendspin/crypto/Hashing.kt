package app.gubbins.sendspin.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** SHA-256, SHA-512, and their HMACs, on top of the platform providers. */
public object Hashing {
    private const val ALGORITHM = "SHA-256"
    private const val ALGORITHM_512 = "SHA-512"

    public fun sha256(data: ByteArray): ByteArray =
        java.security.MessageDigest.getInstance(ALGORITHM).digest(data)

    public fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, ALGORITHM))
        return mac.doFinal(data)
    }

    /**
     * SHA-512, which the CPace construction needs.
     *
     * The Noise layer only ever needs SHA-256. The pairing code exchange
     * needs SHA-512, and the digest is a different length, so it gets its
     * own call rather than a parameter.
     */
    public fun sha512(data: ByteArray): ByteArray =
        java.security.MessageDigest.getInstance(ALGORITHM_512).digest(data)

    /** HMAC-SHA-512, which the CPace confirmation tags need. */
    public fun hmacSha512(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(key, ALGORITHM_512))
        return mac.doFinal(data)
    }

    /**
     * The HKDF that the Noise specification defines, not the one in RFC
     * 5869.
     *
     * The chaining key is the HMAC key and the input key material is the
     * message. The first output is `HMAC(tempKey, 0x01)`, the second is
     * `HMAC(tempKey, output1 || 0x02)`, and so on.
     */
    public fun hkdf(
        chainingKey: ByteArray,
        inputKeyMaterial: ByteArray,
        outputs: Int,
    ): List<ByteArray> {
        require(outputs == 2 || outputs == 3) { "Noise HKDF produces two or three outputs" }
        val tempKey = hmacSha256(chainingKey, inputKeyMaterial)
        val result = ArrayList<ByteArray>(outputs)
        var previous = ByteArray(0)
        for (index in 1..outputs) {
            previous = hmacSha256(tempKey, previous + byteArrayOf(index.toByte()))
            result.add(previous)
        }
        return result
    }
}
