package app.gubbins.spindroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.gubbins.sendspin.pairing.PairingCode
import app.gubbins.sendspin.psk.PskRecord
import app.gubbins.sendspin.session.SessionPhase
import app.gubbins.sendspin.session.SourceSessionState
import app.gubbins.spindroid.R
import app.gubbins.spindroid.capture.CaptureStatus
import app.gubbins.spindroid.mass.AutomaticPairingState
import app.gubbins.spindroid.mass.MassAccountState

/**
 * The sections below the two cards: the pairing, the account, and the
 * status. They serve the setup and the diagnosis, so they sit under
 * the routing that the cards carry.
 */

/**
 * The pairing of the phone with a server.
 *
 * An administrator signs in and the phone pairs on its own, so the user
 * only reads the result here. An account without that right cannot start
 * the setup flow, so the token stays visible for the manual pairing.
 */
@Composable
internal fun PairingSection(
    modifier: Modifier = Modifier,
    pairingToken: String,
    pairingCode: String?,
    serverConnected: Boolean,
    paired: Boolean,
    keptPairings: List<PskRecord>,
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

                keptPairings.isNotEmpty() -> Text(
                    text = stringResource(
                        R.string.pairing_kept,
                        keptPairings.joinToString { it.serverName },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )

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
internal fun AccountSection(
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
internal fun StatusSection(
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
