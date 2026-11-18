package app.gubbins.sendspin.protocol

/**
 * The binary message identifiers.
 *
 * The first byte of every decrypted binary message is one of these. The
 * specification assigns a block of identifiers to each role.
 */
public object MessageId {
    /** A JSON message body, encoded as UTF-8. */
    public const val JSON: Int = 0

    /** A fragment of a message that was too large for one Noise message. */
    public const val FRAGMENTATION: Int = 1

    /** Player audio. */
    public const val PLAYER_AUDIO: Int = 4

    /** Source audio. */
    public const val SOURCE_AUDIO: Int = 12
}

/**
 * The fragmentation layer.
 *
 * A Noise message holds at most 65535 bytes, and the tag takes 16 of them,
 * so the application data in one message is at most 65518 bytes. Anything
 * larger is split across several messages.
 *
 * The wire format of a fragment is:
 *
 * - the first fragment: `[1][flags][orig_type][data]`
 * - the later fragments: `[1][flags][data]`
 *
 * In the flags byte, bit 1 marks the first fragment of a message and bit 0
 * marks the last. The other bits are reserved and must be zero.
 */
public object Fragmentation {
    /** The flag that marks the first fragment of a message. */
    public const val FLAG_FIRST: Int = 0b10

    /** The flag that marks the last fragment of a message. */
    public const val FLAG_LAST: Int = 0b01

    /** The bits that must be zero. */
    public const val RESERVED_MASK: Int = 0b11111100

    /**
     * The largest amount of application data that fits in one Noise message.
     *
     * The message identifier takes one byte, and the AEAD tag takes 16.
     */
    public const val MAX_PAYLOAD_SIZE: Int = 65535 - 16 - 1
}
