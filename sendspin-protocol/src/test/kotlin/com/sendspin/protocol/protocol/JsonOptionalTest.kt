package com.sendspin.protocol.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The difference between a missing field and a field that is null.
 *
 * The specification tells the two apart in `server/state`: omitting a role
 * object leaves that role's state unchanged, while setting it to `null`
 * clears it.
 */
class JsonOptionalTest {
    private val codec = MessageCodec()

    private fun decode(json: String): ServerStatePayload =
        codec.decode(MessageType.SERVER_STATE, json.toByteArray())

    private fun encode(payload: ServerStatePayload): String =
        String(codec.encode(MessageType.SERVER_STATE, payload), Charsets.UTF_8)

    @Test
    fun `a missing role object is absent`() {
        val payload = decode("""{"type":"server/state","payload":{}}""")
        assertFalse(payload.metadata.isPresent)
        assertFalse(payload.controller.isPresent)
        assertFalse(payload.color.isPresent)
    }

    @Test
    fun `a role object set to null is present`() {
        val payload = decode(
            """{"type":"server/state","payload":{"metadata":null,"controller":null,"color":null}}""",
        )
        assertTrue(payload.metadata.isPresent)
        assertTrue(payload.controller.isPresent)
        assertTrue(payload.color.isPresent)
        assertNull(payload.metadata.valueOrNull())
        assertNull(payload.controller.valueOrNull())
        assertNull(payload.color.valueOrNull())
    }

    @Test
    fun `a missing role object and a null one differ`() {
        val missing = decode("""{"type":"server/state","payload":{}}""")
        val nulled = decode("""{"type":"server/state","payload":{"metadata":null}}""")
        assertFalse(missing.metadata.isPresent)
        assertTrue(nulled.metadata.isPresent)
    }

    @Test
    fun `an absent role object is left out of the message`() {
        val json = encode(ServerStatePayload())
        assertFalse(json.contains("metadata"), "The message is $json")
        assertFalse(json.contains("controller"), "The message is $json")
        assertFalse(json.contains("color"), "The message is $json")
    }

    @Test
    fun `a role object set to null is written out`() {
        val json = encode(
            ServerStatePayload(
                metadata = JsonOptional.Present(null),
            ),
        )
        assertTrue(json.contains("\"metadata\":null"), "The message is $json")
    }

    @Test
    fun `a role object with a value is written out`() {
        val json = encode(
            ServerStatePayload(
                metadata = JsonOptional.Present(
                    MetadataState(timestamp = 1_700_000_000_000_000L, title = "A Song"),
                ),
            ),
        )
        assertTrue(json.contains("\"timestamp\":1700000000000000"), "The message is $json")
        assertTrue(json.contains("\"title\":\"A Song\""), "The message is $json")
    }

    @Test
    fun `the roles are independent of each other`() {
        val json = encode(
            ServerStatePayload(
                controller = JsonOptional.Present(
                    ControllerState(
                        supportedCommands = listOf("play", "pause"),
                        volume = 40,
                        muted = false,
                        repeat = "off",
                        shuffle = false,
                    ),
                ),
            ),
        )
        assertFalse(json.contains("metadata"), "The message is $json")
        assertFalse(json.contains("color"), "The message is $json")
        assertTrue(json.contains("\"controller\""), "The message is $json")
    }

    @Test
    fun `a timestamp keeps its exact value`() {
        val timestamp = 9_007_199_254_740_993L
        val payload = ServerStatePayload(
            metadata = JsonOptional.Present(MetadataState(timestamp = timestamp)),
        )
        val decoded = decode(encode(payload))
        assertEquals(
            timestamp,
            (decoded.metadata as JsonOptional.Present).value?.timestamp,
        )
    }

    @Test
    fun `the metadata progress keeps its exact value`() {
        val payload = ServerStatePayload(
            metadata = JsonOptional.Present(
                MetadataState(
                    timestamp = 1L,
                    progress = MetadataProgress(
                        trackProgress = 9_007_199_254_740_993L,
                        trackDuration = 240_000L,
                        playbackSpeed = 1000,
                    ),
                ),
            ),
        )
        val decoded = decode(encode(payload))
        val metadata = (decoded.metadata as JsonOptional.Present).value
        assertEquals(9_007_199_254_740_993L, metadata?.progress?.trackProgress)
        assertEquals(240_000L, metadata?.progress?.trackDuration)
        assertEquals(1000, metadata?.progress?.playbackSpeed)
    }

    @Test
    fun `a color object keeps its channels`() {
        val payload = ServerStatePayload(
            color = JsonOptional.Present(
                ColorState(
                    timestamp = 1L,
                    backgroundDark = listOf(10, 20, 30),
                    primary = listOf(40, 50, 60),
                ),
            ),
        )
        val decoded = decode(encode(payload))
        val color = (decoded.color as JsonOptional.Present).value
        assertEquals(listOf(10, 20, 30), color?.backgroundDark)
        assertEquals(listOf(40, 50, 60), color?.primary)
        assertNull(color?.accent)
    }

    @Test
    fun `the helpers read the value`() {
        val present = JsonOptional.Present("a")
        val nulled = JsonOptional.Present<String>(null)
        val absent = JsonOptional.Absent

        assertTrue(present.isPresent)
        assertTrue(nulled.isPresent)
        assertFalse(absent.isPresent)

        assertEquals("a", present.valueOrNull())
        assertNull(nulled.valueOrNull())
        assertNull(absent.valueOrNull())

        assertEquals("a", present.valueOr("b"))
        assertEquals("b", nulled.valueOr("b"))
        assertEquals("b", absent.valueOr("b"))
    }

    @Test
    fun `an unknown role object is ignored`() {
        // The specification allows objects for application specific roles,
        // with keys that start with an underscore.
        val payload = decode(
            """{"type":"server/state","payload":{"_custom":{"a":1},"metadata":{"timestamp":5}}}""",
        )
        assertTrue(payload.metadata.isPresent)
        assertEquals(5L, (payload.metadata as JsonOptional.Present).value?.timestamp)
    }

    @Test
    fun `the client state tells a missing role object from a null one`() {
        val missing = codec.decode<ClientStatePayload>(
            MessageType.CLIENT_STATE,
            """{"type":"client/state","payload":{"available":true}}""".toByteArray(),
        )
        assertFalse(missing.player.isPresent)
        assertFalse(missing.source.isPresent)

        val nulled = codec.decode<ClientStatePayload>(
            MessageType.CLIENT_STATE,
            """{"type":"client/state","payload":{"available":true,"source":null}}""".toByteArray(),
        )
        assertTrue(nulled.source.isPresent)
        assertNull(nulled.source.valueOrNull())
        assertFalse(nulled.player.isPresent)
    }

    @Test
    fun `the client state leaves out a role object it does not have`() {
        val json = String(
            codec.encode(
                MessageType.CLIENT_STATE,
                ClientStatePayload(
                    available = true,
                    source = JsonOptional.Present(SourceState(signal = "present")),
                ),
            ),
            Charsets.UTF_8,
        )
        assertFalse(json.contains("player"), "The message is $json")
        assertTrue(json.contains("\"signal\":\"present\""), "The message is $json")
    }
}
