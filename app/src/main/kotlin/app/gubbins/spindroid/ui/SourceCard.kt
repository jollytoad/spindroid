package app.gubbins.spindroid.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
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

/**
 * The card that lists the sources.
 *
 * The rows read alphabetically: the apps that hold a media session,
 * each with the transport buttons of its session, and the dimmed rows
 * of the installed media apps, which the open of the app reads on its
 * own. The show-all control at the bottom right reads them again. The
 * pick chooses the one app whose audio the capture should follow.
 */
@Composable
internal fun SourceCard(
    apps: List<SessionApps.SessionApp>,
    picked: String?,
    level: Float,
    missingAccess: Boolean,
    onAllowAccess: () -> Unit,
    restrictedAccessPossible: Boolean,
    onOpenAppInfo: () -> Unit,
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
            Text(
                text = stringResource(R.string.title_source),
                style = MaterialTheme.typography.titleMedium,
            )
            if (apps.isEmpty()) {
                if (missingAccess) {
                    // The grant is what stands between the user and the
                    // list, and only the user can give it, so the card
                    // names the problem and offers the road to its toggle.
                    Text(
                        text = stringResource(R.string.source_access_missing),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = onAllowAccess) {
                        Text(text = stringResource(R.string.source_access_allow))
                    }
                    if (restrictedAccessPossible) {
                        // A sideloaded app meets the restricted settings
                        // dialog at the toggle, and the way out is the ⋮
                        // menu of the app info page. The card takes the
                        // user to that page, because the app cannot open
                        // the menu itself.
                        Text(
                            text = stringResource(R.string.source_access_restricted),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedButton(onClick = onOpenAppInfo) {
                            Text(text = stringResource(R.string.source_access_app_info))
                        }
                    }
                } else {
                    Text(
                        text = stringResource(R.string.source_empty),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
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
                                level = level,
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
            if (!missingAccess) {
                // The show-all control reads the installed media apps
                // again: it picks up apps that arrived since the open,
                // and it brings back the rows a swipe dropped. The
                // control waits for the grant, because a finder answer
                // before the grant would bury the road to the access
                // toggle that the card shows instead.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onOpenFinder) {
                        Text(text = stringResource(R.string.show_all_media_apps))
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
 * picked, the fill of the tint carries the loudness of the captured
 * sound while the row is the one the stream takes, the bars mark the
 * app that plays now, and the one round button on the trailing edge
 * drives the app's session — pause while the app plays, play while it
 * rests, and no button when the session honours neither. A tap on the
 * row, or on its icon, opens the app and picks it, the way a
 * discovered row does: the pick is the only thing the tap changes in
 * the app, because the app that starts to play quiets the others on
 * its own.
 */
@Composable
private fun SourceRow(
    app: SessionApps.SessionApp,
    picked: Boolean,
    level: Float,
    onPick: () -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onOpen: () -> Unit,
) {
    // One tap takes both roads at once: the pick arms the capture for
    // the moment the app plays, and the open brings the app to the
    // front. Nothing else happens — a session that starts playing
    // quiets the other apps through the system, not through this app.
    val openAndPick = {
        onPick()
        onOpen()
    }
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
            // The fill rides the tint of the pick, and a row that is
            // not picked carries no stream, so it stays flat.
            .levelFill(if (picked) level else 0f)
            .selectable(
                selected = picked,
                role = Role.RadioButton,
                onClick = openAndPick,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AppIcon(
            packageName = app.packageName,
            label = app.label,
            onOpen = openAndPick,
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
                    PlayingBars(description = stringResource(R.string.bars_playing))
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
 * The app's own icon, with the label's first letter in its place when
 * the package has no icon to draw.
 *
 * The icon draws whole and wears one rounded square, whatever shape
 * its own art carries: a crop to the launcher's visible zone cut the
 * art that some apps lay past it, and the rows read better with one
 * shape. The letter stands in on the harness too, where the offered
 * packages are names, not installed apps. A tap on the icon opens
 * the app.
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
                modifier = Modifier
                    .size(IconSize)
                    .clip(IconShape),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(IconSize)
                    .background(MaterialTheme.colorScheme.primary, IconShape)
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

/** The rounded square that every app icon wears, real art or letter. */
private val IconShape = RoundedCornerShape(10.dp)

/** Draws one package icon into a bitmap of the size the row shows. */
private fun drawableToBitmap(drawable: Drawable, sizePx: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    // The icon draws whole: an adaptive icon carries its own padding
    // on the canvas, and a crop to the visible zone cut the art that
    // some apps lay past it, like a wordmark or a letter set. The
    // rounded square of the row masks the shape.
    drawable.setBounds(0, 0, sizePx, sizePx)
    drawable.draw(canvas)
    return bitmap
}

/**
 * One app that the finder found, and that holds no session yet.
 *
 * The row is dimmed and carries no transport buttons. A swipe to the
 * left drops it from the list until the next run of the finder: the
 * shared swipe box drives the gesture, and the row shrinks and fades
 * away, so the rows under it rise in step with the collapse. The
 * model drops the row only once the collapse has settled. A tap on
 * the row opens the app and picks it, so the pick waits for the app
 * to play.
 */
@Composable
private fun DiscoveredRow(
    app: SessionApps.SessionApp,
    picked: Boolean,
    onPick: () -> Unit,
    onDiscard: () -> Unit,
    onOpen: () -> Unit,
) {
    SwipeAsideRow(onDrop = onDiscard) {
        DiscoveredFace(
            app = app,
            picked = picked,
            onPick = onPick,
            onOpen = onOpen,
        )
    }
}

/**
 * The face of one discovered app: its icon and its name, dimmed beside
 * the session rows. The face covers the band at rest. A tap on the
 * face, or on the icon inside it, opens the app and picks it: the app
 * comes to the front, and the capture follows it once it plays.
 */
@Composable
private fun DiscoveredFace(
    app: SessionApps.SessionApp,
    picked: Boolean,
    onPick: () -> Unit,
    onOpen: () -> Unit,
) {
    // One tap takes both roads at once: the pick arms the capture for
    // the moment the app plays, and the open brings the app to the
    // front so the user can start it playing.
    val openAndPick = {
        onPick()
        onOpen()
    }
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
                onClick = openAndPick,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AppIcon(
            packageName = app.packageName,
            label = app.label,
            onOpen = openAndPick,
        )
        Text(
            text = app.label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
