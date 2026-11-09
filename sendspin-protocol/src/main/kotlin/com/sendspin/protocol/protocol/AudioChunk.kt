package com.sendspin.protocol.protocol

import java.nio.ByteBuffer

/**
 * The binary messages that carry audio.
 *
 * A source audio message tells the server when the audio was captured. A
 * player audio message tells the player when to play it, and how much lead
 * the server had.
 */
public object AudioChunk {
    /**
     * Builds a source audio message.
     *
     * @param timestampMicros the server time of the first sample, in
     *   microseconds
     * @param audio the encoded audio frame
     */
    public fun source(timestampMicros: Long, audio: ByteArray): ByteArray {
        val out = ByteBuffer.allocate(1 + sourceBody(timestampMicros, audio).size)
        out.put(MessageId.SOURCE_AUDIO.toByte())
        out.put(sourceBody(timestampMicros, audio))
        return out.array()
    }

    /**
     * Builds the body of a source audio message, without the type byte.
     *
     * The framing layer takes the body on its own, because it adds the type
     * byte itself.
     *
     * @param timestampMicros the server time of the first sample, in
     *   microseconds
     * @param audio the encoded audio frame
     */
    public fun sourceBody(timestampMicros: Long, audio: ByteArray): ByteArray {
        val out = ByteBuffer.allocate(8 + audio.size)
        out.putLong(timestampMicros)
        out.put(audio)
        return out.array()
    }

    /**
     * Builds a player audio message.
     *
     * @param timestampMicros the server time at which the first sample
     *   should be played, in microseconds
     * @param sendAheadMicros the lead the server had, in microseconds. A
     *   negative value means the server was late, and a value too large to
     *   fit is saturated.
     * @param audio the encoded audio frame
     */
    public fun player(timestampMicros: Long, sendAheadMicros: Long, audio: ByteArray): ByteArray {
        val out = ByteBuffer.allocate(1 + 8 + 4 + audio.size)
        out.put(MessageId.PLAYER_AUDIO.toByte())
        out.putLong(timestampMicros)
        out.putInt(saturateSendAhead(sendAheadMicros))
        out.put(audio)
        return out.array()
    }

    /**
     * The lead the server reports.
     *
     * The field is 32 bits wide, so a lead that does not fit is saturated at
     * the largest value. A lead of zero means the server measured no lead at
     * all.
     */
    public fun saturateSendAhead(sendAheadMicros: Long): Int {
        if (sendAheadMicros <= 0) return 0
        if (sendAheadMicros >= UINT32_MAX) return UINT32_MAX.toInt()
        return sendAheadMicros.toInt()
    }

    /** Reads the timestamp and the audio out of a source audio message. */
    public fun parseSource(message: ByteArray): SourceAudio {
        require(message.size >= 9) { "A source audio message is at least 9 bytes" }
        val buffer = ByteBuffer.wrap(message)
        val id = buffer.get().toInt() and 0xFF
        require(id == MessageId.SOURCE_AUDIO) { "Not a source audio message" }
        val timestamp = buffer.long
        val audio = ByteArray(buffer.remaining())
        buffer.get(audio)
        return SourceAudio(timestamp, audio)
    }

    /** Reads the timestamp, the lead and the audio out of a player audio message. */
    public fun parsePlayer(message: ByteArray): PlayerAudio {
        require(message.size >= 13) { "A player audio message is at least 13 bytes" }
        val buffer = ByteBuffer.wrap(message)
        val id = buffer.get().toInt() and 0xFF
        require(id == MessageId.PLAYER_AUDIO) { "Not a player audio message" }
        val timestamp = buffer.long
        val sendAhead = buffer.int.toLong() and 0xFFFFFFFFL
        val audio = ByteArray(buffer.remaining())
        buffer.get(audio)
        return PlayerAudio(timestamp, sendAhead, audio)
    }

    private const val UINT32_MAX = 4294967295L

    /** A decoded source audio message. */
    public data class SourceAudio(val timestampMicros: Long, val audio: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SourceAudio) return false
            return timestampMicros == other.timestampMicros && audio.contentEquals(other.audio)
        }

        override fun hashCode(): Int = 31 * timestampMicros.hashCode() + audio.contentHashCode()
    }

    /** A decoded player audio message. */
    public data class PlayerAudio(
        val timestampMicros: Long,
        val sendAheadMicros: Long,
        val audio: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is PlayerAudio) return false
            return timestampMicros == other.timestampMicros &&
                sendAheadMicros == other.sendAheadMicros &&
                audio.contentEquals(other.audio)
        }

        override fun hashCode(): Int {
            var result = timestampMicros.hashCode()
            result = 31 * result + sendAheadMicros.hashCode()
            result = 31 * result + audio.contentHashCode()
            return result
        }
    }
}
