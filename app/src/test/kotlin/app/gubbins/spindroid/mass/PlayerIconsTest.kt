package app.gubbins.spindroid.mass

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.SpeakerGroup
import androidx.compose.material.icons.rounded.Tv
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The rule that names a player's glyph.
 *
 * The server sends the name, and the name can be any word of the icon
 * set, because the user sets it in the server's settings. Every word
 * the mapping does not carry reads as a plain speaker, so an unknown
 * word never leaves the row without a glyph.
 */
class PlayerIconsTest {

    @Test
    fun `the names of the server map to their glyphs`() {
        assertEquals(Icons.Rounded.Cast, playerIconOf("cast"))
        assertEquals(Icons.Rounded.Tv, playerIconOf("tv"))
        assertEquals(Icons.Rounded.Laptop, playerIconOf("mac"))
        assertEquals(Icons.Rounded.SpeakerGroup, playerIconOf("speakers"))
    }

    @Test
    fun `the prefixed spelling maps to the same glyph`() {
        assertEquals(playerIconOf("speaker"), playerIconOf("mdi-speaker"))
    }

    @Test
    fun `a word the list does not carry reads as a speaker`() {
        assertEquals(Icons.Rounded.Speaker, playerIconOf("homepod-mini"))
        assertEquals(Icons.Rounded.Speaker, playerIconOf("voice-pe"))
    }

    @Test
    fun `no word reads as a speaker`() {
        assertEquals(Icons.Rounded.Speaker, playerIconOf(null))
    }
}
