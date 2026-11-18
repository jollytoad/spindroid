package app.gubbins.sendspin.psk

import app.gubbins.sendspin.crypto.Base64Url
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The text format that stores pairing records.
 *
 * The earlier format separated the fields with spaces, so a server name such
 * as `Music Assistant (studio)` broke it. These tests hold the format to that
 * case and to the older one.
 */
class PskRecordCodecTest {
    private fun psk(seed: Int): ByteArray = ByteArray(32) { (seed + it).toByte() }

    private fun record(serverId: String, seed: Int, name: String) = PskRecord(
        serverId = serverId,
        psk = psk(seed),
        serverName = name,
        category = PskCategory.LONG_TERM,
    )

    @Test
    fun `keeps a server name with spaces and brackets`() {
        val input = listOf(record("server-1", 1, "Music Assistant (studio)"))
        assertEquals(input, PskRecordCodec.decode(PskRecordCodec.encode(input)))
    }

    @Test
    fun `keeps a server name with the separator and a line break`() {
        val input = listOf(record("server-1", 1, "a|b\nc"))
        assertEquals(input, PskRecordCodec.decode(PskRecordCodec.encode(input)))
    }

    @Test
    fun `keeps the order and the field values of several records`() {
        val input = listOf(
            record("server-1", 1, "Music Assistant (living room)"),
            record("server-2", 2, "Kitchen"),
        )
        assertEquals(input, PskRecordCodec.decode(PskRecordCodec.encode(input)))
    }

    @Test
    fun `reads the older space separated format`() {
        val legacy = "server-1 Kitchen ${Base64Url.encode(psk(3))}"
        assertEquals(
            listOf(record("server-1", 3, "Kitchen")),
            PskRecordCodec.decode(legacy),
        )
    }

    @Test
    fun `skips a malformed line and keeps the rest`() {
        val good = record("server-2", 2, "Music Assistant (studio)")
        val text = "1|server-1|not-base64!|AAAA\n" + PskRecordCodec.encode(listOf(good))
        assertEquals(listOf(good), PskRecordCodec.decode(text))
    }

    @Test
    fun `skips a legacy line whose name holds a space`() {
        val text = "server-1 Music Assistant (studio) ${Base64Url.encode(psk(1))}"
        assertEquals(emptyList<PskRecord>(), PskRecordCodec.decode(text))
    }
}
