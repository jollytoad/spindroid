package app.gubbins.spindroid.mass

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Airplay
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Monitor
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.SpeakerGroup
import androidx.compose.material.icons.rounded.Tablet
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The glyph that a player's row shows.
 *
 * Music Assistant sends the icon of a player as the name of a Material
 * Design icon. The name comes from the server's own guess, or from what
 * the user set in the server's settings, so it can be any word. The rows
 * draw the glyph the word names, and every word the list below does not
 * carry reads as a plain speaker.
 *
 * The server writes some names with the `mdi-` prefix of the icon set,
 * and some without, so both spellings resolve to the same glyph.
 */
fun playerIconOf(icon: String?): ImageVector {
    val name = icon?.lowercase()?.removePrefix("mdi-")
        ?: return Icons.Rounded.Speaker
    return when (name) {
        "speakers" -> Icons.Rounded.SpeakerGroup
        "airplay" -> Icons.Rounded.Airplay
        "cast" -> Icons.Rounded.Cast
        "tv", "apple-tv" -> Icons.Rounded.Tv
        "monitor" -> Icons.Rounded.Monitor
        "laptop", "mac" -> Icons.Rounded.Laptop
        "smartphone" -> Icons.Rounded.Smartphone
        "headphones" -> Icons.Rounded.Headphones
        "radio" -> Icons.Rounded.Radio
        "bluetooth" -> Icons.Rounded.Bluetooth
        "car", "carplay" -> Icons.Rounded.DirectionsCar
        "vinyl" -> Icons.Rounded.Album
        "sun" -> Icons.Rounded.LightMode
        "tablet" -> Icons.Rounded.Tablet
        else -> Icons.Rounded.Speaker
    }
}
