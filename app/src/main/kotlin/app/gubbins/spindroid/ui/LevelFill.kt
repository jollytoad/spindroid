package app.gubbins.spindroid.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import kotlin.math.sqrt

/**
 * The loudness of the capture, as the share of a row that the fill covers.
 *
 * The capture reports the loudest sample of its window, which is a
 * number between silence and the full scale of a 16-bit sample. The
 * fill needs the share of the row, and music rarely comes near the
 * full scale, so the curve bends: a square root lifts the quiet range
 * where the music lives, so an ordinary loudness still moves the edge
 * of the fill across the row.
 *
 * A window of silence reports zero, and zero fills nothing, so a row
 * that carries no sound keeps its plain tint.
 */
internal fun fillLevelOf(peak: Int): Float =
    sqrt(peak.coerceIn(0, FULL_SCALE_PEAK) / FULL_SCALE_PEAK.toFloat())

/** The loudest sample a 16-bit capture can report. */
private const val FULL_SCALE_PEAK = 32767

/**
 * Draws the sound level as a fill on the row's own tint.
 *
 * The picked row is the row that carries the stream, so its highlight
 * is the one the eye must find. A moving fill is easier to find than
 * a colour that sits close to the colour of the row beside it, and
 * the fill needs no room of its own: it rides behind the text, from
 * the left edge of the row to the share the level names.
 *
 * The capture reports the level four times a second, so a short chase
 * between the reports keeps the edge of the fill moving smoothly. The
 * chase writes no composition state — the drawing reads the value in
 * its own phase, so a moving fill redraws the row alone.
 *
 * @param level the share of the row that the sound fills, from the
 *   [fillLevelOf] curve; zero draws nothing
 */
@Composable
internal fun Modifier.levelFill(level: Float): Modifier {
    // The fill is a shade of the pick tint, not a colour of its own:
    // the moving edge is the signal, and the text keeps its contrast.
    val color = MaterialTheme.colorScheme.primary.copy(alpha = FILL_ALPHA)
    val animated = remember { Animatable(0f) }
    LaunchedEffect(level) {
        animated.animateTo(level, tween(durationMillis = CHASE_MILLIS, easing = LinearEasing))
    }
    return drawBehind {
        val fraction = animated.value.coerceIn(0f, 1f)
        if (fraction <= 0f) return@drawBehind
        drawRoundRect(
            color = color,
            cornerRadius = CornerRadius(RowRadius.toPx()),
            size = Size(size.width * fraction, size.height),
        )
    }
}

/** The share of the primary colour that the fill lays over the tint. */
private const val FILL_ALPHA = 0.3f

/** The chase between two reports of the capture, a quarter of a second. */
private const val CHASE_MILLIS = 250

/** The radius of the row, so the fill meets the row's own corners. */
private val RowRadius = 28.dp
