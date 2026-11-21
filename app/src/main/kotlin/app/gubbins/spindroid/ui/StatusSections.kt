package app.gubbins.spindroid.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gubbins.sendspin.session.SessionPhase
import app.gubbins.sendspin.session.SourceSessionState
import app.gubbins.spindroid.R
import app.gubbins.spindroid.capture.CaptureStatus

/**
 * The technical details below the two cards.
 *
 * They serve the diagnosis, not the routing, so they fold away: the
 * title row alone stands on the screen, and a tap on it opens or
 * closes the rest.
 */

/** The technical details of the connection and the capture. */
@Composable
internal fun StatusSection(
    state: SourceSessionState,
    captureStatus: CaptureStatus,
    buildLabel: String,
) {
    // The fold rests through a screen turn, and every fresh open
    // starts folded.
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.title_technical_guff),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                // The arrow points down while the details fold and up
                // while they stand, so the icon says where the tap goes.
                Icon(
                    painter = painterResource(R.drawable.ic_expand),
                    contentDescription = stringResource(
                        if (expanded) {
                            R.string.technical_guff_collapse
                        } else {
                            R.string.technical_guff_expand
                        },
                    ),
                    modifier = Modifier.rotate(if (expanded) 180f else 0f),
                )
            }
            if (expanded) {
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
}

/** One line of the details. */
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
