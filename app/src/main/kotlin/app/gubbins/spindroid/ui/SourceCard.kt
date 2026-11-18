package app.gubbins.spindroid.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gubbins.spindroid.R
import app.gubbins.spindroid.capture.SessionApps
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
    onOpen: (String) -> Unit,
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
                                onOpen = { onOpen(app.packageName) },
                            )
                        } else {
                            DiscoveredRow(
                                app = app,
                                picked = app.packageName == picked,
                                onPick = { onPick(app.packageName) },
                                onDiscard = { onDiscard(app.packageName) },
                                onOpen = { onOpen(app.packageName) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * One source app in the list.
 *
 * The row is the pick control itself: the tint says whether the row is
 * picked, the bars mark the app that plays now, and the one round
 * button on the trailing edge drives the app's session — pause while
 * the app plays, play while it rests, and no button when the session
 * honours neither. Tapping the app's icon opens the app; tapping
 * anywhere else on the row picks it.
 */
@Composable
private fun SourceRow(
    app: SessionApps.SessionApp,
    picked: Boolean,
    onPick: () -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (picked) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
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
        AppIcon(
            packageName = app.packageName,
            label = app.label,
            onOpen = onOpen,
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                )
                if (app.playingNow) {
                    PlayingBars()
                }
            }
            app.nowPlaying?.let { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
            }
        }
        when {
            app.playingNow && app.canPause -> OutlinedIconButton(onClick = onPause) {
                PauseMark(description = stringResource(R.string.action_pause))
            }

            !app.playingNow && app.canPlay -> OutlinedIconButton(onClick = onPlay) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = stringResource(R.string.action_play),
                )
            }
        }
    }
}

/**
 * The pause mark of the row button.
 *
 * The core icon set carries no pause glyph, so the mark draws the two
 * bars the mockup shows, in the content colour the button sets. The
 * description carries the verb, so the button reads the same as an
 * icon would.
 */
@Composable
private fun PauseMark(description: String, modifier: Modifier = Modifier) {
    val color = LocalContentColor.current
    Row(
        modifier = modifier
            .height(16.dp)
            .semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(4.dp, 16.dp).background(color, RoundedCornerShape(2.dp)))
        Box(Modifier.size(4.dp, 16.dp).background(color, RoundedCornerShape(2.dp)))
    }
}

/**
 * The mark that says the app plays now.
 *
 * Three bars of different heights read as sound, the way every player
 * shows it. The mark carries no description, because the row already
 * names the app, and the sound speaks for itself.
 */
@Composable
private fun PlayingBars(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Row(
        modifier = modifier.height(16.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Box(Modifier.size(3.dp, 12.dp).background(color, RoundedCornerShape(2.dp)))
        Box(Modifier.size(3.dp, 16.dp).background(color, RoundedCornerShape(2.dp)))
        Box(Modifier.size(3.dp, 10.dp).background(color, RoundedCornerShape(2.dp)))
    }
}

/**
 * The app's own icon, with the label's first letter in its place when
 * the package has no icon to draw.
 *
 * The letter stands in on the harness too, where the offered packages
 * are names, not installed apps. A tap on the icon opens the app.
 */
@Composable
private fun AppIcon(
    packageName: String,
    label: String,
    onOpen: () -> Unit,
) {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { IconSize.roundToPx() }
    val bitmap = remember(packageName, sizePx) {
        runCatching {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            drawableToBitmap(drawable, sizePx).asImageBitmap()
        }.getOrNull()
    }
    IconButton(onClick = onOpen) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = stringResource(R.string.open_app, label),
                modifier = Modifier.size(IconSize),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(IconSize)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                    .semantics {
                        contentDescription = context.getString(R.string.open_app, label)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label.take(1),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

/** The edge of the icon that the rows show. */
private val IconSize = 40.dp

/** Draws one package icon into a bitmap of the size the row shows. */
private fun drawableToBitmap(drawable: Drawable, sizePx: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    // An adaptive icon carries a rim of invisible padding, so the
    // drawing crops a quarter aside on every edge, and the art fills
    // the box the way the launcher shows it.
    val rim = if (drawable is AdaptiveIconDrawable) sizePx * 0.25f else 0f
    drawable.setBounds(
        -rim.toInt(),
        -rim.toInt(),
        (sizePx + rim).toInt(),
        (sizePx + rim).toInt(),
    )
    drawable.draw(canvas)
    return bitmap
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
 * the app; tapping the app's icon opens it.
 */
@Composable
private fun DiscoveredRow(
    app: SessionApps.SessionApp,
    picked: Boolean,
    onPick: () -> Unit,
    onDiscard: () -> Unit,
    onOpen: () -> Unit,
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
                    onOpen = onOpen,
                )
            }
        }
    }
}

/**
 * The face of one discovered app: its icon and its name, dimmed beside
 * the session rows. The face covers the band at rest. Tapping the face
 * picks the app; tapping the icon opens it.
 */
@Composable
private fun DiscoveredFace(
    app: SessionApps.SessionApp,
    picked: Boolean,
    onPick: () -> Unit,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceContainerHighest,
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
        AppIcon(
            packageName = app.packageName,
            label = app.label,
            onOpen = onOpen,
        )
        Text(
            text = app.label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
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
