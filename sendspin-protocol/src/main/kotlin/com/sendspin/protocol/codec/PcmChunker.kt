package com.sendspin.protocol.codec

/**
 * Splits the captured audio into the chunks that the protocol carries.
 *
 * The specification wants each chunk to hold between five and one hundred
 * fifty milliseconds of audio. The capture delivers whatever the device
 * hands over, which may be much less and may be much more, so the samples
 * are gathered until a chunk is full.
 *
 * A chunk carries the time of its first sample. That time is the time of
 * the first captured block that put a sample into the chunk, because the
 * capture reports the time of its first sample and not the time of every
 * sample in it.
 */
public class PcmChunker(
    private val sampleRate: Int,
    private val channels: Int,
    private val chunkMillis: Int = DEFAULT_CHUNK_MILLIS,
) {
    /** One chunk of audio. */
    public class Chunk(
        /** The samples, interleaved by channel. */
        public val samples: ShortArray,
        /** The local time of the first sample, in microseconds. */
        public val timestampMicros: Long,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Chunk) return false
            return samples.contentEquals(other.samples) &&
                timestampMicros == other.timestampMicros
        }

        override fun hashCode(): Int =
            31 * samples.contentHashCode() + timestampMicros.hashCode()
    }

    /** The number of frames in a full chunk. */
    public val framesPerChunk: Int =
        if (sampleRate <= 0) 0 else (sampleRate * chunkMillis) / 1000

    private val buffer = ShortArray(framesPerChunk * channels)
    private var filled = 0
    private var chunkTimestamp = 0L

    /**
     * Adds a block of captured samples.
     *
     * @param samples the samples, interleaved by channel
     * @param timestampMicros the local time of the first sample, in
     *   microseconds
     * @return the chunks that are now complete
     */
    public fun offer(samples: ShortArray, timestampMicros: Long): List<Chunk> {
        val out = mutableListOf<Chunk>()
        if (framesPerChunk <= 0 || channels <= 0) return out
        var offset = 0
        while (offset < samples.size) {
            if (filled == 0) {
                // The chunk starts with this block, so it carries the time
                // of the block's first sample.
                chunkTimestamp = timestampMicros
            }
            val room = buffer.size - filled
            val take = minOf(room, samples.size - offset)
            samples.copyInto(buffer, filled, offset, offset + take)
            filled += take
            offset += take
            if (filled == buffer.size) {
                out += Chunk(buffer.copyOf(), chunkTimestamp)
                filled = 0
            }
        }
        return out
    }

    /**
     * Returns the samples that are left over.
     *
     * The leftover is shorter than a full chunk. It is sent at the end of
     * the stream, so the server does not lose the last samples.
     */
    public fun flush(): List<Chunk> {
        if (filled == 0) return emptyList()
        val out = listOf(Chunk(buffer.copyOf(filled), chunkTimestamp))
        filled = 0
        return out
    }

    /** The number of samples that are waiting for a full chunk. */
    public val pendingSamples: Int get() = filled

    public companion object {
        /**
         * The length of a chunk, in milliseconds.
         *
         * The specification allows five to one hundred fifty milliseconds.
         * Twenty milliseconds keeps the delay low and stays well inside the
         * bounds.
         */
        public const val DEFAULT_CHUNK_MILLIS: Int = 20
    }
}
