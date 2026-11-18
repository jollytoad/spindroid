package com.sendspin.sender.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.sendspin.sender.R
import com.sendspin.sender.capture.SessionApps
import kotlinx.coroutines.flow.first

/**
 * The card that lists the sources.
 *
 * The rows read alphabetically: the apps that hold a media session,
 * each with the transport buttons of its session, and, when the finder
 * ran, the dimmed rows of the apps it found. The pick chooses the one
 * app whose audio the capture should follow.
 */
@Composable
internal fun SourceCard(
    apps: List<SessionApps.SessionApp>,
    picked: String?,
    onPick: (String) -> Unit,
    onPlay: (String) -> Unit,
    onPause: (String) -> Unit,
    onOpenFinder: () -> Unit,
    onDiscard: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.title_source),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onOpenFinder) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = stringResource(R.string.finder_open),
                    )
                }
            }
            if (apps.isEmpty()) {
                Text(
                    text = stringResource(R.string.source_empty),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                // A row carries remembered state, and the list changes in
                // the middle when a discard lands, so every row keeps
                // its own composition key. Without the key, the row
                // under a discarded one inherits the discarded row's
                // collapse state, because the plain column remembers by
                // position.
                apps.forEach { app ->
                    key(app.packageName) {
                        if (app.holdsSession) {
                            SourceRow(
                                app = app,
                                picked = app.packageName == picked,
                                onPick = { onPick(app.packageName) },
                                onPlay = { onPlay(app.packageName) },
                                onPause = { onPause(app.packageName) },
                            )
                        } else {
                            DiscoveredRow(
                                app = app,
                                picked = app.packageName == picked,
                                onPick = { onPick(app.packageName) },
                                onDiscard = { onDiscard(app.packageName) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One source app in the list. */
@Composable
private fun SourceRow(
    app: SessionApps.SessionApp,
    picked: Boolean,
    onPick: () -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = picked, onClick = onPick)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = app.label, style = MaterialTheme.typography.bodyLarge)
            app.nowPlaying?.let { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
            }
        }
        TextButton(onClick = onPlay, enabled = app.canPlay) {
            Text(stringResource(R.string.action_play))
        }
        TextButton(onClick = onPause, enabled = app.canPause) {
            Text(stringResource(R.string.action_pause))
        }
    }
}

/**
 * One app that the finder found, and that holds no session yet.
 *
 * The row is dimmed and carries no transport buttons. A swipe to the
 * left discards it, the way an email app archives a message: the
 * native swipe box drives the gesture, and the row shrinks and fades
 * away, so the rows under it rise in step with the collapse. The
 * model drops the row only once the collapse has settled, and the
 * snackbar with the undo shows over the screen. Tapping the row picks
 * the app and opens it.
 */
@Composable
private fun DiscoveredRow(
    app: SessionApps.SessionApp,
    picked: Boolean,
    onPick: () -> Unit,
    onDiscard: () -> Unit,
) {
    // The visible state of the row is the one truth of its leaving: the
    // swipe sets the target to false, the collapse animation runs to
    // that target, and the effect below hands the discard over once
    // the collapse has settled.
    val shown = remember { MutableTransitionState(true) }

    LaunchedEffect(shown) {
        // An idle state whose target is false means the collapse ended.
        snapshotFlow { shown.isIdle && !shown.targetState }.first { it }
        onDiscard()
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
                DiscoveredFace(
                    app = app,
                    picked = picked,
                    onPick = onPick,
                )
            }
        }
    }
}

/**
 * The face of one discovered app: its initial and its name, dimmed
 * beside the session rows. The face covers the band at rest.
 */
@Composable
private fun DiscoveredFace(
    app: SessionApps.SessionApp,
    picked: Boolean,
    onPick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(28.dp),
            )
            .selectable(
                selected = picked,
                role = Role.RadioButton,
                onClick = onPick,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = app.label.take(1),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
        Text(
            text = app.label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
private fun DiscardBand(progress: Float) {
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
