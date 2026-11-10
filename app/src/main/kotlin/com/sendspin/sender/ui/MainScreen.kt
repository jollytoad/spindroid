package com.sendspin.sender.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.sendspin.protocol.session.SessionPhase
import com.sendspin.protocol.session.SourceSessionState
import com.sendspin.sender.R
import com.sendspin.sender.storage.CaptureSource
import com.sendspin.sender.storage.ConnectionDirection

/**
 * The screen of the app.
 *
 * It has three parts: where the audio comes from, the token that pairs the
 * phone, and the state of the connection.
 */
@Composable
fun MainScreen(
    serverAddress: String,
    captureSource: CaptureSource,
    direction: ConnectionDirection,
    pairingToken: String,
    state: SourceSessionState,
    running: Boolean,
    message: String?,
    onServerAddressChange: (String) -> Unit,
    onCaptureSourceChange: (CaptureSource) -> Unit,
    onDirectionChange: (ConnectionDirection) -> Unit,
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

        ConnectionSection(
            serverAddress = serverAddress,
            captureSource = captureSource,
            direction = direction,
            running = running,
            onServerAddressChange = onServerAddressChange,
            onCaptureSourceChange = onCaptureSourceChange,
            onDirectionChange = onDirectionChange,
            onConnect = onConnect,
            onDisconnect = onDisconnect,
        )

        PairingSection(pairingToken = pairingToken, onCopyToken = onCopyToken)

        StatusSection(state = state)

        if (message != null) {
            Text(text = message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Where the audio comes from, and the button that starts the capture. */
@Composable
private fun ConnectionSection(
    serverAddress: String,
    captureSource: CaptureSource,
    direction: ConnectionDirection,
    running: Boolean,
    onServerAddressChange: (String) -> Unit,
    onCaptureSourceChange: (CaptureSource) -> Unit,
    onDirectionChange: (ConnectionDirection) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.title_server),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.label_direction),
                style = MaterialTheme.typography.titleSmall,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SourceChoice(
                    label = stringResource(R.string.direction_dial_out),
                    selected = direction == ConnectionDirection.DIAL_OUT,
                    enabled = !running,
                    onSelect = { onDirectionChange(ConnectionDirection.DIAL_OUT) },
                )
                Spacer(modifier = Modifier.width(12.dp))
                SourceChoice(
                    label = stringResource(R.string.direction_accept_incoming),
                    selected = direction == ConnectionDirection.ACCEPT_INCOMING,
                    enabled = !running,
                    onSelect = { onDirectionChange(ConnectionDirection.ACCEPT_INCOMING) },
                )
            }
            OutlinedTextField(
                value = serverAddress,
                onValueChange = onServerAddressChange,
                label = { Text(stringResource(R.string.label_server_address)) },
                placeholder = { Text(stringResource(R.string.hint_server_address)) },
                singleLine = true,
                enabled = !running && direction == ConnectionDirection.DIAL_OUT,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.label_capture_source),
                style = MaterialTheme.typography.titleSmall,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SourceChoice(
                    label = stringResource(R.string.capture_system_audio),
                    selected = captureSource == CaptureSource.SYSTEM_AUDIO,
                    enabled = !running,
                    onSelect = { onCaptureSourceChange(CaptureSource.SYSTEM_AUDIO) },
                )
                Spacer(modifier = Modifier.width(12.dp))
                SourceChoice(
                    label = stringResource(R.string.capture_microphone),
                    selected = captureSource == CaptureSource.MICROPHONE,
                    enabled = !running,
                    onSelect = { onCaptureSourceChange(CaptureSource.MICROPHONE) },
                )
            }
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

/** One of the capture sources. */
@Composable
private fun SourceChoice(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onSelect, enabled = enabled)
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** The token that pairs the phone with a server. */
@Composable
private fun PairingSection(pairingToken: String, onCopyToken: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.title_pairing),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.pairing_explanation),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.pairing_where),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.label_pairing_token),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = pairingToken,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            val code = remember(pairingToken) { QrCode.render(pairingToken, QR_SIZE) }
            if (code != null) {
                Text(
                    text = stringResource(R.string.label_qr_code),
                    style = MaterialTheme.typography.titleSmall,
                )
                Image(
                    bitmap = code.asImageBitmap(),
                    contentDescription = stringResource(R.string.label_qr_code),
                    modifier = Modifier.height(QR_SIZE.dp),
                )
            }
            OutlinedButton(onClick = onCopyToken) {
                Text(stringResource(R.string.action_copy_token))
            }
        }
    }
}

/** What the connection is doing. */
@Composable
private fun StatusSection(state: SourceSessionState) {
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
            if (state.awaitingApproval) {
                HorizontalDivider()
                // The server holds the connection until the operator
                // approves the client in Music Assistant.
                Text(
                    text = stringResource(R.string.state_awaiting_approval),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
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

/** The size of the pairing code, in pixels. */
private const val QR_SIZE = 512
