package com.sendspin.sender.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.sendspin.mass.MassPlayer
import com.sendspin.protocol.pairing.PairingCode
import com.sendspin.protocol.session.SessionPhase
import com.sendspin.protocol.session.SourceSessionState
import com.sendspin.sender.R
import com.sendspin.sender.capture.CaptureStatus
import com.sendspin.sender.mass.MassAccountState
import com.sendspin.sender.mass.MassPlayerState

/**
 * The screen of the app.
 *
 * It has three parts: the button that starts the capture, the token that
 * pairs the phone, and the state of the connection.
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
    sourceUri: String,
    clientId: String,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onCopyToken: () -> Unit,
    onSignIn: (serverUrl: String, username: String, password: String) -> Unit,
    onSignOut: () -> Unit,
    onServerUrlChange: (String) -> Unit,
    onRefreshPlayers: () -> Unit,
    onChoosePlayer: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
        )

        StartSection(running = running, onConnect = onConnect, onDisconnect = onDisconnect)

        PairingSection(
            pairingToken = pairingToken,
            pairingCode = state.pairingCode,
            serverConnected = state.serverName != null,
            paired = state.paired,
            onCopyToken = onCopyToken,
        )

        AccountSection(
            account = account,
            onSignIn = onSignIn,
            onSignOut = onSignOut,
            onServerUrlChange = onServerUrlChange,
        )

        PlayerPickerSection(
            state = players,
            sourceUri = sourceUri,
            clientId = clientId,
            signedIn = account.signedIn,
            live = state.paired,
            onRefresh = onRefreshPlayers,
            onChoose = onChoosePlayer,
        )

        StatusSection(state = state, captureStatus = captureStatus, buildLabel = buildLabel)

        if (message != null) {
            Text(text = message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** The button that starts the capture. */
@Composable
private fun StartSection(
    running: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.title_start),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.start_explanation),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (running) {
                OutlinedButton(onClick = onDisconnect) {
                    Text(stringResource(R.string.action_disconnect))
                }
            } else {
                Button(onClick = onConnect) {
                    Text(stringResource(R.string.action_connect))
                }
            }
        }
    }
}

/** The token that pairs the phone with a server, and the code that does it faster. */
@Composable
private fun PairingSection(
    pairingToken: String,
    pairingCode: String?,
    serverConnected: Boolean,
    paired: Boolean,
    onCopyToken: () -> Unit,
) {
    // The token is shown when the user asks for it, and also while no
    // server has called in, because then it is the only way to pair.
    var tokenVisible by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.title_pairing),
                style = MaterialTheme.typography.titleMedium,
            )
            if (pairingCode != null) {
                Text(
                    text = stringResource(R.string.pairing_code_instruction),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = PairingCode.group(pairingCode),
                    style = MaterialTheme.typography.displayMedium.copy(fontFamily = FontFamily.Monospace),
                )
            } else if (paired) {
                Text(
                    text = stringResource(R.string.pairing_done),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else if (serverConnected) {
                Text(
                    text = stringResource(R.string.pairing_code_where),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(
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
            if (tokenVisible || !serverConnected) {
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
 * The sign in to Music Assistant.
 *
 * The account is what lets the app ask the server which players there are.
 * The fields hold the name and the password, so they are cleared once the
 * sign in has worked, and the password never stays on the screen.
 */
@Composable
private fun AccountSection(
    account: MassAccountState,
    onSignIn: (serverUrl: String, username: String, password: String) -> Unit,
    onSignOut: () -> Unit,
    onServerUrlChange: (String) -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
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
                OutlinedTextField(
                    value = account.serverUrl,
                    onValueChange = onServerUrlChange,
                    label = { Text(stringResource(R.string.label_account_server)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.label_account_username)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.label_account_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        onSignIn(account.serverUrl, username, password)
                        password = ""
                    },
                    enabled = !account.signingIn && account.serverUrl.isNotBlank(),
                ) {
                    Text(
                        stringResource(
                            if (account.signingIn) {
                                R.string.action_signing_in
                            } else {
                                R.string.action_sign_in
                            },
                        ),
                    )
                }
                account.error?.let { error ->
                    Text(text = error, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/**
 * The choice of the player that plays the phone.
 *
 * The phone cannot make sound itself. The server decides where the sound
 * goes, so the list comes from the server, and the choice is told to the
 * server.
 */
@Composable
private fun PlayerPickerSection(
    state: MassPlayerState,
    sourceUri: String,
    clientId: String,
    signedIn: Boolean,
    live: Boolean,
    onRefresh: () -> Unit,
    onChoose: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.title_players),
                style = MaterialTheme.typography.titleMedium,
            )
            if (!signedIn) {
                Text(
                    text = stringResource(R.string.players_sign_in_first),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(
                    text = stringResource(R.string.players_explanation),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!live) {
                    Text(
                        text = stringResource(R.string.players_not_live),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                when {
                    state.loading && state.players.isEmpty() -> Text(
                        text = stringResource(R.string.players_loading),
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    state.players.isEmpty() -> Text(
                        text = stringResource(R.string.players_none),
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    else -> state.players.forEach { player ->
                        PlayerRow(
                            player = player,
                            names = state.allPlayers.associate { it.playerId to it.label },
                            selected = player.activeSource == sourceUri ||
                                player.activeSource == clientId,
                            busy = state.choosing == player.playerId,
                            usable = live,
                            onChoose = { onChoose(player.playerId) },
                        )
                    }
                }
                state.error?.let { error ->
                    Text(text = error, style = MaterialTheme.typography.bodyMedium)
                }
                OutlinedButton(onClick = onRefresh, enabled = !state.loading) {
                    Text(stringResource(R.string.action_refresh))
                }
            }
        }
    }
}

/** One player in the list. */
@Composable
private fun PlayerRow(
    player: MassPlayer,
    names: Map<String, String>,
    selected: Boolean,
    busy: Boolean,
    usable: Boolean,
    onChoose: () -> Unit,
) {
    val canChoose = usable && player.available && !busy
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = canChoose, onClick = onChoose),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = onChoose, enabled = canChoose)
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
