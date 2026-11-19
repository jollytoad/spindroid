package app.gubbins.spindroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The mark that says a row is live.
 *
 * Three bars of different heights read as sound, the way every player
 * shows it. The Source card shows it beside the app that plays now, and
 * the Destination card shows it beside the player that the stream plays
 * on. The description names the state it marks, so a screen reader says
 * what the bars stand for.
 *
 * @param description the words that name the state, such as "playing
 *   now" on a source row
 */
@Composable
internal fun PlayingBars(
    description: String,
    modifier: Modifier = Modifier,
) {
    val color = MaterialTheme.colorScheme.primary
    Row(
        modifier = modifier
            .height(16.dp)
            .semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Box(Modifier.size(3.dp, 12.dp).background(color, RoundedCornerShape(2.dp)))
        Box(Modifier.size(3.dp, 16.dp).background(color, RoundedCornerShape(2.dp)))
        Box(Modifier.size(3.dp, 10.dp).background(color, RoundedCornerShape(2.dp)))
    }
}
