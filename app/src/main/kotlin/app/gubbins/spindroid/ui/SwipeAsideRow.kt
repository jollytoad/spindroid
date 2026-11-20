package app.gubbins.spindroid.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first

/**
 * One row of a card that a swipe to the left drops from the list.
 *
 * The native swipe box drives the gesture, and the row shrinks and
 * fades away, so the rows under it rise in step with the collapse.
 * The caller drops the row from its model only once the collapse has
 * settled; the drop is reversible through the control that fills the
 * list again.
 */
@Composable
internal fun SwipeAsideRow(
    onDrop: () -> Unit,
    face: @Composable () -> Unit,
) {
    // The visible state of the row is the one truth of its leaving: the
    // swipe sets the target to false, the collapse animation runs to
    // that target, and the effect below hands the drop over once the
    // collapse has settled.
    val shown = remember { MutableTransitionState(true) }

    LaunchedEffect(shown) {
        // An idle state whose target is false means the collapse ended.
        snapshotFlow { shown.isIdle && !shown.targetState }.first { it }
        onDrop()
    }

    AnimatedVisibility(
        visibleState = shown,
        exit = fadeOut() + shrinkVertically(),
    ) {
        // The clip holds the sliding face inside the row, the way the
        // card holds every row inside its own bounds.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp)),
        ) {
            val state = rememberSwipeToDismissBoxState(
                confirmValueChange = { value ->
                    when (value) {
                        SwipeToDismissBoxValue.EndToStart -> {
                            shown.targetState = false
                            true
                        }
                        else -> false
                    }
                },
                positionalThreshold = { totalDistance -> totalDistance * 0.4f },
            )
            SwipeToDismissBox(
                state = state,
                enableDismissFromStartToEnd = false,
                backgroundContent = {
                    // The M3 pattern paints nothing at rest, so the
                    // band shows only while the swipe displaces the
                    // face, and its colour follows the swipe.
                    when (state.dismissDirection) {
                        SwipeToDismissBoxValue.EndToStart ->
                            DiscardBand(progress = state.progress)
                        else -> {}
                    }
                },
            ) {
                face()
            }
        }
    }
}

/**
 * The band the swipe reveals behind the row.
 *
 * The band paints only while the face slides aside, and its colour
 * follows the swipe, so the discard grows in with the drag. The trash
 * mark speaks through the discard itself, which the row already names
 * for the screen reader, so the mark carries no description of its
 * own.
 */
@Composable
internal fun DiscardBand(progress: Float) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .background(
                MaterialTheme.colorScheme.error.copy(alpha = progress),
                RoundedCornerShape(28.dp),
            )
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
    ) {
        Icon(
            imageVector = Icons.Outlined.Delete,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onError.copy(alpha = progress),
        )
    }
}
