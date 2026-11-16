package com.sendspin.sender.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.sendspin.mass.MassPlayer
import com.sendspin.protocol.pairing.PairingCode
import com.sendspin.protocol.session.SessionPhase
import com.sendspin.protocol.session.SourceSessionState
import com.sendspin.sender.R
import com.sendspin.sender.capture.CaptureStatus
import com.sendspin.sender.capture.SessionApps
import com.sendspin.sender.mass.AutomaticPairingState
import com.sendspin.sender.mass.DiscoveredServer
import com.sendspin.sender.mass.MassAccountState
import com.sendspin.sender.mass.MassPlayerState
import kotlinx.coroutines.launch

/**
 * The screen of the app.
 *
 * Two cards carry the routing: the Source card lists the apps that hold a
 * media session, and the Destination card lists the players that can play
 * the phone. The Connect control in the bar streams the picked source to
 * the picked destination. The pairing and the status stay below, because
 * they serve the setup and the diagnosis.
 */
@Composable
fun MainScreen(
    pairingToken: String,
    state: SourceSessionState,
    captureStatus: CaptureStatus,
    buildLabel: String,
    running: Boolean,
    message: String?,
    account: MassAccountState,
    players: MassPlayerState,
    automaticPairing: AutomaticPairingState,
    discoveredServer: DiscoveredServer?,
    discoveryError: String?,
    sessionApps: List<SessionApps.SessionApp>,
    pickedSession: String?,
    playerPick: String?,
    onPickSession: (String) -> Unit,
    onSessionPlay: (String) -> Unit,
    onSessionPause: (String) -> Unit,
    onPickPlayer: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onCopyToken: () -> Unit,
    onOpenSignIn: () -> Unit,
    onSignOut: () -> Unit,
) {
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    // The place of the pairing section that the onboarding option points
    // at. The scroll offset is in the same space as the position in the
    // column, so the button reaches the section without knowing its height.
    var pairingTop by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TopBar(
            running = running,
            connectEnabled = running || (pickedSession != null && playerPick != null),
            onConnect = onConnect,
            onDisconnect = onDisconnect,
        )

        if (message != null) {
            Text(text = message, style = MaterialTheme.typography.bodyMedium)
        }

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            if (maxWidth >= WIDE_WIDTH) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    SourceCard(
                        apps = sessionApps,
                        picked = pickedSession,
                        onPick = onPickSession,
                        onPlay = onSessionPlay,
                        onPause = onSessionPause,
                        modifier = Modifier.weight(1f),
                    )
                    DestinationCard(
                        server = discoveredServer,
                        error = discoveryError,
                        account = account,
                        players = players,
                        live = state.paired,
                        running = running,
                        playerPick = playerPick,
                        signedIn = account.signedIn,
                        onSignIn = onOpenSignIn,
                        onPair = { scope.launch { scrollState.animateScrollTo(pairingTop) } },
                        onPick = onPickPlayer,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SourceCard(
                        apps = sessionApps,
                        picked = pickedSession,
                        onPick = onPickSession,
                        onPlay = onSessionPlay,
                        onPause = onSessionPause,
                    )
                    DestinationCard(
                        server = discoveredServer,
                        error = discoveryError,
                        account = account,
                        players = players,
                        live = state.paired,
                        running = running,
                        playerPick = playerPick,
                        signedIn = account.signedIn,
                        onSignIn = onOpenSignIn,
                        onPair = { scope.launch { scrollState.animateScrollTo(pairingTop) } },
                        onPick = onPickPlayer,
                    )
                }
            }
        }

        PairingSection(
            modifier = Modifier.onGloballyPositioned {
                pairingTop = it.positionInParent().y.toInt()
            },
            pairingToken = pairingToken,
            pairingCode = state.pairingCode,
            serverConnected = state.serverName != null,
            paired = state.paired,
            automaticPairing = automaticPairing,
            signedIn = account.signedIn,
            administrator = account.administrator,
            onCopyToken = onCopyToken,
        )

        AccountSection(
            account = account,
            onOpenSignIn = onOpenSignIn,
            onSignOut = onSignOut,
        )

        StatusSection(state = state, captureStatus = captureStatus, buildLabel = buildLabel)
    }
}

/** The bar that names the app and holds the stream control. */
@Composable
private fun TopBar(
    running: Boolean,
    connectEnabled: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.weight(1f),
        )
        if (running) {
            Button(onClick = onDisconnect, enabled = true) {
                Text(stringResource(R.string.action_disconnect))
            }
        } else {
            Button(onClick = onConnect, enabled = connectEnabled) {
                Text(stringResource(R.string.action_connect))
            }
        }
    }
}

/**
 * The card that lists the sources.
 *
 * The rows are the apps that hold a media session, playing ones first,
 * each with the transport buttons of its session. The pick chooses the
 * one app whose audio the capture should follow.
 */
@Composable
private fun SourceCard(
    apps: List<SessionApps.SessionApp>,
    picked: String?,
    onPick: (String) -> Unit,
    onPlay: (String) -> Unit,
    onPause: (String) -> Unit,
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
                Text(
                    text = stringResource(R.string.source_empty),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                apps.forEach { app ->
                    SourceRow(
                        app = app,
                        picked = app.packageName == picked,
                        onPick = { onPick(app.packageName) },
                        onPlay = { onPlay(app.packageName) },
                        onPause = { onPause(app.packageName) },
                    )
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
 * The card that holds the destination.
 *
 * Before the account exists, the card holds the Music Assistant that the
 * phone found and the two ways to connect. After it, the card lists the
 * players, and the pick chooses the one player that plays the phone.
 */
@Composable
private fun DestinationCard(
    server: DiscoveredServer?,
    error: String?,
    account: MassAccountState,
    players: MassPlayerState,
    live: Boolean,
    running: Boolean,
    playerPick: String?,
    signedIn: Boolean,
    onSignIn: () -> Unit,
    onPair: () -> Unit,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.title_destination),
                style = MaterialTheme.typography.titleMedium,
            )
            when {
                signedIn -> {
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
                            PlayerRow(
                                player = player,
                                names = players.allPlayers.associate { it.playerId to it.label },
                                picked = playerPick == player.playerId,
                                busy = players.choosing == player.playerId,
                                canPick = !running && player.available,
                                onPick = { onPick(player.playerId) },
                            )
                        }
                    }
                    players.error?.let { message ->
                        Text(text = message, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                server != null -> {
                    Text(
                        text = server.name,
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        text = stringResource(R.string.server_explanation),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.option_sign_in))
                    }
                    OutlinedButton(onClick = onPair, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.option_pair))
                    }
                    Text(
                        text = stringResource(R.string.players_sign_in_first),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

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

/** One player in the list. */
@Composable
private fun PlayerRow(
    player: MassPlayer,
    names: Map<String, String>,
    picked: Boolean,
    busy: Boolean,
    canPick: Boolean,
    onPick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = canPick && !busy, onClick = onPick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = picked, onClick = onPick, enabled = canPick && !busy)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = player.label, style = MaterialTheme.typography.bodyLarge)
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

/**
 * The pairing of the phone with a server.
 *
 * An administrator signs in and the phone pairs on its own, so the user
 * only reads the result here. An account without that right cannot start
 * the setup flow, so the token stays visible for the manual pairing.
 */
@Composable
private fun PairingSection(
    modifier: Modifier = Modifier,
    pairingToken: String,
    pairingCode: String?,
    serverConnected: Boolean,
    paired: Boolean,
    automaticPairing: AutomaticPairingState,
    signedIn: Boolean,
    administrator: Boolean,
    onCopyToken: () -> Unit,
) {
    // The token is shown when the user asks for it, and also while no
    // server has called in, because then it is the only way to pair.
    var tokenVisible by remember { mutableStateOf(false) }
    val failure = automaticPairing as? AutomaticPairingState.Failed
    val manual = signedIn && !administrator
    val showToken = tokenVisible || !serverConnected || manual || failure != null
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.title_pairing),
                style = MaterialTheme.typography.titleMedium,
            )
            when {
                pairingCode != null -> {
                    Text(
                        text = stringResource(R.string.pairing_code_instruction),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = PairingCode.group(pairingCode),
                        style = MaterialTheme.typography.displayMedium.copy(fontFamily = FontFamily.Monospace),
                    )
                }

                paired || automaticPairing is AutomaticPairingState.Paired -> Text(
                    text = stringResource(R.string.pairing_paired),
                    style = MaterialTheme.typography.bodyMedium,
                )

                automaticPairing is AutomaticPairingState.Pairing -> Text(
                    text = stringResource(R.string.pairing_in_progress),
                    style = MaterialTheme.typography.bodyMedium,
                )

                failure != null -> {
                    Text(
                        text = stringResource(R.string.pairing_failed, failure.reason),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(R.string.pairing_explanation),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                manual -> Text(
                    text = stringResource(R.string.pairing_explanation),
                    style = MaterialTheme.typography.bodyMedium,
                )

                serverConnected -> Text(
                    text = stringResource(R.string.pairing_code_where),
                    style = MaterialTheme.typography.bodyMedium,
                )

                else -> Text(
                    text = stringResource(R.string.pairing_explanation),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            TextButton(onClick = { tokenVisible = !tokenVisible }) {
                Text(
                    stringResource(
                        if (tokenVisible) R.string.action_hide_token else R.string.action_show_token,
                    ),
                )
            }
            if (showToken) {
                Text(
                    text = stringResource(R.string.label_pairing_token),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = pairingToken,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
                OutlinedButton(onClick = onCopyToken) {
                    Text(stringResource(R.string.action_copy_token))
                }
            }
        }
    }
}

/**
 * The state of the account on the main page.
 *
 * The form itself lives on its own page, because the user needs it only
 * once. This section tells the state, offers the way to the page, and
 * offers the sign out.
 */
@Composable
private fun AccountSection(
    account: MassAccountState,
    onOpenSignIn: () -> Unit,
    onSignOut: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.title_account),
                style = MaterialTheme.typography.titleMedium,
            )
            if (account.signedIn) {
                Text(
                    text = stringResource(
                        R.string.account_signed_in,
                        account.displayName ?: account.username.orEmpty(),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = onSignOut) {
                    Text(stringResource(R.string.action_sign_out))
                }
            } else {
                Text(
                    text = stringResource(R.string.account_explanation),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = onOpenSignIn) {
                    Text(stringResource(R.string.action_sign_in))
                }
                account.error?.let { error ->
                    Text(text = error, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** What the connection is doing. */
@Composable
private fun StatusSection(
    state: SourceSessionState,
    captureStatus: CaptureStatus,
    buildLabel: String,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.title_status),
                style = MaterialTheme.typography.titleMedium,
            )
            StatusRow(
                label = stringResource(R.string.label_connection),
                value = stringResource(state.phase.label()),
            )
            StatusRow(
                label = stringResource(R.string.label_server_name),
                value = state.serverName ?: stringResource(R.string.value_no_server),
            )
            StatusRow(
                label = stringResource(R.string.label_paired),
                value = if (state.paired) {
                    stringResource(R.string.value_yes)
                } else {
                    stringResource(R.string.value_not_paired)
                },
            )
            if (state.pairedServers.isNotEmpty()) {
                StatusRow(
                    label = stringResource(R.string.label_paired_servers),
                    value = state.pairedServers.joinToString(", "),
                )
            }
            StatusRow(
                label = stringResource(R.string.label_clock_error),
                value = state.clockErrorMicros?.let { "$it µs" } ?: "—",
            )
            StatusRow(
                label = stringResource(R.string.label_stream),
                value = stringResource(
                    when {
                        state.streaming -> R.string.stream_running
                        state.serverRequestedAudio -> R.string.stream_waiting
                        else -> R.string.stream_stopped
                    },
                ),
            )
            HorizontalDivider()
            StatusRow(
                label = stringResource(R.string.label_capture),
                value = stringResource(
                    if (captureStatus.running) {
                        R.string.value_capture_running
                    } else {
                        R.string.value_capture_stopped
                    },
                ),
            )
            StatusRow(
                label = stringResource(R.string.label_capture_level),
                value = stringResource(R.string.value_capture_level, captureStatus.peak * 100 / MAX_PEAK),
            )
            StatusRow(
                label = stringResource(R.string.label_capture_rate),
                value = stringResource(R.string.value_capture_rate, captureStatus.blocksPerSecond),
            )
            captureStatus.error?.let { error ->
                HorizontalDivider()
                Text(text = error, style = MaterialTheme.typography.bodyMedium)
            }
            HorizontalDivider()
            StatusRow(
                label = stringResource(R.string.label_build),
                value = buildLabel,
            )
            state.error?.let { error ->
                HorizontalDivider()
                Text(text = error, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** One line of the status. */
@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** The text that shows the phase of the session. */
private fun SessionPhase.label(): Int = when (this) {
    SessionPhase.IDLE -> R.string.state_idle
    SessionPhase.HANDSHAKING -> R.string.state_handshaking
    SessionPhase.HELLO -> R.string.state_hello
    SessionPhase.SYNCING -> R.string.state_syncing
    SessionPhase.READY -> R.string.state_ready
    SessionPhase.STREAMING -> R.string.state_streaming
    SessionPhase.CLOSED -> R.string.state_closed
}

/** The loudest sample of a 16-bit block. */
private const val MAX_PEAK = 32767

/** The width from which the two cards sit side by side. */
private val WIDE_WIDTH = 600.dp
