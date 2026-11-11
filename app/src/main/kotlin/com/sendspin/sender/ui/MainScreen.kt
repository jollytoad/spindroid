package com.sendspin.sender.ui

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
import com.sendspin.protocol.pairing.PairingCode
import com.sendspin.protocol.session.SessionPhase
import com.sendspin.protocol.session.SourceSessionState
import com.sendspin.sender.R
import com.sendspin.sender.capture.CaptureStatus

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
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onCopyToken: () -> Unit,
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
