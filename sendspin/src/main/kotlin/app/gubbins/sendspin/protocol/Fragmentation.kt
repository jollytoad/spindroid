package app.gubbins.sendspin.protocol

/**
 * The result of offering a message to the fragment reassembler.
 */
public sealed interface FrameResult {
    /** The message is complete. */
    public data class Complete(val messageId: Int, val payload: ByteArray) : FrameResult {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Complete) return false
            return messageId == other.messageId && payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int = 31 * messageId + payload.contentHashCode()
    }

    /** The message is a fragment, and more are expected. */
    public data object Incomplete : FrameResult

    /** The message breaks the rules. The connection must close. */
    public data class Malformed(val reason: String) : FrameResult
}

/**
 * Reassembles fragmented messages.
 *
 * Only one fragmented message may be in flight in a direction at a time, so
 * the reassembler holds a single buffer. Anything that breaks the rules
 * produces a [FrameResult.Malformed], and the caller closes the connection.
 */
public class FragmentReassembler {
    private var inFlight = false
    private var originalType = 0
    private var buffer = ByteArray(0)

    /**
     * Offers one decrypted binary message to the reassembler.
     *
     * @param message the message, starting with its message identifier
     */
    public fun offer(message: ByteArray): FrameResult {
        if (message.isEmpty()) {
            return FrameResult.Malformed("A binary message must not be empty")
        }
        val id = message[0].toInt() and 0xFF
        if (id != MessageId.FRAGMENTATION) {
            // A plain message must not arrive while a fragmented message is
            // still being assembled.
            if (inFlight) {
                return FrameResult.Malformed("A plain message arrived during a fragmented message")
            }
            return FrameResult.Complete(id, message.copyOfRange(1, message.size))
        }

        if (message.size < 2) {
            return FrameResult.Malformed("A fragment must carry a flags byte")
        }
        val flags = message[1].toInt() and 0xFF
        if (flags and Fragmentation.RESERVED_MASK != 0) {
            return FrameResult.Malformed("A fragment set a reserved flag bit")
        }
        val isFirst = flags and Fragmentation.FLAG_FIRST != 0
        val isLast = flags and Fragmentation.FLAG_LAST != 0

        if (isFirst) {
            if (inFlight) {
                return FrameResult.Malformed("A first fragment arrived during a fragmented message")
            }
            if (message.size < 3) {
                return FrameResult.Malformed("A first fragment must carry the original message type")
            }
            val type = message[2].toInt() and 0xFF
            if (type == MessageId.FRAGMENTATION) {
                return FrameResult.Malformed("A fragment cannot carry a fragment as its original type")
            }
            inFlight = true
            originalType = type
            buffer = message.copyOfRange(3, message.size)
        } else {
            if (!inFlight) {
                return FrameResult.Malformed("A continuation fragment arrived without a first fragment")
            }
            buffer += message.copyOfRange(2, message.size)
        }

        if (!isLast) return FrameResult.Incomplete

        inFlight = false
        val complete = FrameResult.Complete(originalType, buffer)
        buffer = ByteArray(0)
        return complete
    }

    /** Whether a fragmented message is still being assembled. */
    public val hasMessageInFlight: Boolean get() = inFlight

    /** Forgets any partial message. */
    public fun reset() {
        inFlight = false
        originalType = 0
        buffer = ByteArray(0)
    }
}

/**
 * Splits a large message into fragments.
 */
public object Fragmenter {
    /**
     * Splits a message into fragments.
     *
     * A message that fits in one Noise message is returned unchanged, as a
     * single message with its own identifier.
     *
     * @param messageId the message identifier
     * @param payload the message body
     */
    public fun fragment(messageId: Int, payload: ByteArray): List<ByteArray> {
        require(messageId != MessageId.FRAGMENTATION) {
            "A message cannot be a fragment of a fragment"
        }
        if (payload.size <= Fragmentation.MAX_PAYLOAD_SIZE) {
            return listOf(byteArrayOf(messageId.toByte()) + payload)
        }

        // The first fragment carries the original message type, so it holds
        // one byte less of payload.
        val firstCapacity = Fragmentation.MAX_PAYLOAD_SIZE - 2
        val restCapacity = Fragmentation.MAX_PAYLOAD_SIZE - 1
        val fragments = ArrayList<ByteArray>()
        var offset = 0
        var first = true
        while (offset < payload.size) {
            val capacity = if (first) firstCapacity else restCapacity
            val end = minOf(offset + capacity, payload.size)
            val isLast = end == payload.size
            var flags = 0
            if (first) flags = flags or Fragmentation.FLAG_FIRST
            if (isLast) flags = flags or Fragmentation.FLAG_LAST
            val fragment = if (first) {
                byteArrayOf(
                    MessageId.FRAGMENTATION.toByte(),
                    flags.toByte(),
                    messageId.toByte(),
                ) + payload.copyOfRange(offset, end)
            } else {
                byteArrayOf(MessageId.FRAGMENTATION.toByte(), flags.toByte()) +
                    payload.copyOfRange(offset, end)
            }
            fragments.add(fragment)
            offset = end
            first = false
        }
        return fragments
    }
}
