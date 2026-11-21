package app.gubbins.spindroid.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gubbins.mass.MassPlayer
import app.gubbins.spindroid.R
import app.gubbins.spindroid.mass.DiscoveredServer
import app.gubbins.spindroid.mass.MassAccountState
import app.gubbins.spindroid.mass.MassPlayerState
import app.gubbins.spindroid.mass.playerIconOf

/**
 * The card that holds the destination.
 *
 * The card always opens with the row of the Music Assistant server,
 * once the phone found one or signed in to one: the row names the
 * server, and its button carries the way in or the way out. After the
 * sign in, the card also lists the players, and the row pick sends the
 * audio of the phone to the one player it names. A swipe aside drops a
 * row from the list until the refresh control beside the title runs,
 * which reads the list again and forgets every drop. The row that
 * carries the pick refuses the swipe, the way a session row does on
 * the Source card.
 */
@Composable
internal fun DestinationCard(
    server: DiscoveredServer?,
    error: String?,
    account: MassAccountState,
    players: MassPlayerState,
    live: Boolean,
    running: Boolean,
    level: Float,
    playerPick: String?,
    signedIn: Boolean,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onPick: (String) -> Unit,
    onDiscard: (String) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.title_destination),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                // The list exists only with an account, so the refresh
                // control sits with it. Each run forgets the drops, the
                // way the finder of the Source card does.
                if (signedIn) {
                    IconButton(onClick = onRefresh) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.players_refresh),
                        )
                    }
                }
            }
            when {
                signedIn -> {
                    // The row stays above the players, so the card keeps
                    // naming the server the list came from, and keeps the
                    // way out in reach.
                    ServerRow(
                        subtitle = server?.name ?: account.serverUrl,
                        signedIn = true,
                        onSignIn = onSignIn,
                        onSignOut = onSignOut,
                    )
                    if (!live) {
                        Text(
                            text = stringResource(R.string.players_not_live),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    when {
                        players.loading && players.players.isEmpty() -> Text(
                            text = stringResource(R.string.players_loading),
                            style = MaterialTheme.typography.bodyMedium,
                        )

                        players.players.isEmpty() -> Text(
                            text = stringResource(R.string.players_none),
                            style = MaterialTheme.typography.bodyMedium,
                        )

                        else -> players.players.forEach { player ->
                            // A row carries remembered state, and the list
                            // changes in the middle when a discard lands,
                            // so every row keeps its own composition key.
                            key(player.playerId) {
                                if (playerPick == player.playerId) {
                                    // The picked row is the one the stream
                                    // goes to, so it refuses the swipe, the
                                    // way a session row does on the Source
                                    // card: the row that carries the stream
                                    // cannot be dropped.
                                    PlayerRow(
                                        player = player,
                                        names = players.allPlayers.associate { it.playerId to it.label },
                                        picked = true,
                                        live = player.playerId == players.livePlayerId,
                                        level = level,
                                        busy = players.choosing == player.playerId,
                                        canPick = player.available,
                                        onPick = { onPick(player.playerId) },
                                    )
                                } else {
                                    SwipeAsideRow(
                                        onDrop = { onDiscard(player.playerId) },
                                    ) {
                                        PlayerRow(
                                            player = player,
                                            names = players.allPlayers.associate { it.playerId to it.label },
                                            picked = false,
                                            live = player.playerId == players.livePlayerId,
                                            level = level,
                                            busy = players.choosing == player.playerId,
                                            canPick = player.available,
                                            onPick = { onPick(player.playerId) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    players.error?.let { message ->
                        Text(text = message, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                server != null -> ServerRow(
                    subtitle = server.name,
                    signedIn = false,
                    onSignIn = onSignIn,
                    onSignOut = onSignOut,
                )

                error != null -> Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                )

                else -> Text(
                    text = stringResource(R.string.server_searching),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/**
 * The row of the Music Assistant server.
 *
 * The row names the server that the phone talks to, whatever way the
 * phone learned of it: the search names the server it found, and the
 * account names the one it signed in to when the search stays quiet.
 * The button on the right carries the way in, or the way out.
 */
@Composable
private fun ServerRow(
    subtitle: String,
    signedIn: Boolean,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(28.dp),
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // The art of the Music Assistant app, the icon its own project
        // ships, under the Apache 2.0 licence.
        Image(
            painter = painterResource(R.drawable.ic_music_assistant),
            contentDescription = null,
            modifier = Modifier
                .size(GlyphSize)
                .clip(GlyphShape),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.mass_row_title),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
            )
        }
        if (signedIn) {
            OutlinedButton(onClick = onSignOut) {
                Text(stringResource(R.string.action_sign_out))
            }
        } else {
            OutlinedButton(onClick = onSignIn) {
                Text(stringResource(R.string.action_sign_in))
            }
        }
    }
}

/**
 * One player in the list.
 *
 * The row is the send control itself, the way a source row is its own
 * pick: the tint says whether the row carries the choice, the fill of
 * the tint carries the loudness of the sound that the stream sends,
 * the bars mark the player that the stream plays on now, and the glyph
 * names the kind of the player the way the server sees it. Tapping
 * anywhere on the row sends the audio of the phone to this player.
 * The row serves as the face of the swipe box on the rows that a
 * swipe can drop.
 */
@Composable
private fun PlayerRow(
    player: MassPlayer,
    names: Map<String, String>,
    picked: Boolean,
    live: Boolean,
    level: Float,
    busy: Boolean,
    canPick: Boolean,
    onPick: () -> Unit,
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
            // The fill rides the tint of the pick, and a row that is
            // not picked carries no stream, so it stays flat.
            .levelFill(if (picked) level else 0f)
            .clickable(
                enabled = canPick && !busy,
                onClickLabel = stringResource(R.string.players_send),
                role = Role.RadioButton,
                onClick = onPick,
            )
            // The row is its own selection control, the way a source row
            // is: the clickable above gives the row its action, and this
            // gives it the state the pick reads.
            .semantics { selected = picked }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = playerIconOf(player.icon),
            contentDescription = null,
            modifier = Modifier.size(GlyphSize),
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = player.label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                )
                if (live) {
                    PlayingBars(description = stringResource(R.string.bars_live_output))
                }
            }
            noteOf(player, names)?.let { note ->
                Text(text = note, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * The line under the name of a player.
 *
 * A group holds other players, and the line names them. A player that
 * cannot be reached says so.
 */
@Composable
private fun noteOf(player: MassPlayer, names: Map<String, String>): String? {
    if (!player.available) return stringResource(R.string.players_not_available)
    if (player.isGroup) {
        val members = player.groupMembers.mapNotNull { names[it] }
        if (members.isNotEmpty()) return members.joinToString(", ")
    }
    return player.provider
}

/** The edge of the glyph that the rows show, the size of a source row icon. */
private val GlyphSize = 40.dp

/** The rounded square that the server glyph wears. */
private val GlyphShape = RoundedCornerShape(10.dp)
