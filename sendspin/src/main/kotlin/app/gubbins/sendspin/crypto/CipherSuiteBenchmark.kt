package app.gubbins.sendspin.crypto

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A measurement of how fast AES-GCM runs on this device.
 *
 * Some devices have no AES hardware, and on those the software AES is slow
 * enough to matter for a stream of audio. The client measures the cipher
 * once, at startup, and picks the faster suite.
 *
 * The measurement runs once per process. The result is cached, because the
 * cipher does not change while the process runs.
 */
public object CipherSuiteBenchmark {
    /** The number of operations in one measurement. */
    public const val OPERATIONS: Int = 200

    /** The size of one operation's buffer, in bytes. */
    public const val BUFFER_SIZE: Int = 4096

    /**
     * The time one operation may take, in nanoseconds.
     *
     * A source sends about fifty chunks a second. At this threshold the
     * cipher uses well under one percent of the processor.
     */
    public const val THRESHOLD_NANOS: Long = 50_000

    /**
     * The time one AES-GCM operation takes, in nanoseconds.
     *
     * The value is measured once and then reused.
     */
    public val nanosecondsPerOperation: Long by lazy { measure() }

    /**
     * Whether AES-GCM runs fast enough on this device.
     */
    public fun isAesFastEnough(): Boolean =
        nanosecondsPerOperation < THRESHOLD_NANOS

    /**
     * The suite the client should use.
     */
    public fun preferredSuite(): NoiseSuite =
        if (isAesFastEnough()) NoiseSuite.AES_GCM_SHA256 else NoiseSuite.CHACHA_POLY_SHA256

    /**
     * Measures the cipher.
     *
     * The measurement runs a few operations first, so that the virtual
     * machine has compiled the cipher before the clock starts.
     */
    private fun measure(): Long {
        val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        val nonce = ByteArray(12)
        val buffer = ByteArray(BUFFER_SIZE)

        // Warm up.
        repeat(10) { encrypt(key, nonce, buffer) }

        val start = System.nanoTime()
        repeat(OPERATIONS) { encrypt(key, nonce, buffer) }
        val elapsed = System.nanoTime() - start
        return elapsed / OPERATIONS
    }

    private fun encrypt(key: SecretKeySpec, nonce: ByteArray, data: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        cipher.doFinal(data)
    }
}
