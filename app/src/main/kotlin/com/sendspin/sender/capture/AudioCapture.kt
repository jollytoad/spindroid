package com.sendspin.sender.capture

/**
 * A block of captured audio.
 *
 * The samples are little-endian signed 16-bit values, interleaved by
 * channel. The timestamp is the local time of the first sample, in
 * microseconds.
 */
data class PcmBlock(
    /** The samples, interleaved by channel. */
    val samples: ShortArray,
    /** The number of channels. */
    val channels: Int,
    /** The sample rate, in hertz. */
    val sampleRate: Int,
    /** The local time of the first sample, in microseconds. */
    val captureTimestampMicros: Long,
) {
    /** The number of frames in the block. */
    val frames: Int get() = if (channels == 0) 0 else samples.size / channels

    /** The size of the block in bytes. */
    val byteCount: Int get() = samples.size * 2

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PcmBlock) return false
        return samples.contentEquals(other.samples) &&
            channels == other.channels &&
            sampleRate == other.sampleRate &&
            captureTimestampMicros == other.captureTimestampMicros
    }

    override fun hashCode(): Int {
        var result = samples.contentHashCode()
        result = 31 * result + channels
        result = 31 * result + sampleRate
        result = 31 * result + captureTimestampMicros.hashCode()
        return result
    }
}

/**
 * A source of captured audio.
 *
 * The capture runs on its own thread and hands the blocks to a listener.
 */
interface AudioCapture {
    /**
     * Starts the capture.
     *
     * @param listener where the blocks go
     */
    fun start(listener: CaptureListener)

    /** Stops the capture. */
    fun stop()

    /** Whether the capture is running. */
    val isRunning: Boolean
}

/** Where the captured blocks go. */
interface CaptureListener {
    /** One block of audio. */
    fun onBlock(block: PcmBlock)

    /** The capture failed. */
    fun onError(message: String)
}
