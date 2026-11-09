package com.sendspin.protocol.session

/**
 * The audio format a source announces and sends.
 *
 * The client picks the format and tells the server about it in
 * `client-stream/start`. The server resamples and transcodes, so the format
 * only has to be one the client can actually produce.
 */
public data class SourceAudioFormat(
    /** The codec. Only PCM is planned. */
    public val codec: String = "pcm",
    /** The sample rate, in hertz. */
    public val sampleRate: Int = 48000,
    /** The number of channels. */
    public val channels: Int = 2,
    /** The number of bits per sample. */
    public val bitDepth: Int = 16,
) {
    init {
        require(codec == "pcm") { "Only the pcm codec is planned" }
        require(sampleRate > 0) { "The sample rate must be positive" }
        require(channels > 0) { "The channel count must be positive" }
        require(bitDepth == 16) { "Only 16-bit samples are planned" }
    }

    /** The number of bytes one sample of all channels takes. */
    public val bytesPerFrame: Int get() = channels * bitDepth / 8

    /** The number of frames in a chunk of the given length in milliseconds. */
    public fun framesFor(millis: Int): Int = sampleRate * millis / 1000
}
