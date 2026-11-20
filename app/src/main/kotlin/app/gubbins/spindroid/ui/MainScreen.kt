package app.gubbins.spindroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gubbins.sendspin.psk.PskRecord
import app.gubbins.sendspin.session.SourceSessionState
import app.gubbins.spindroid.R
import app.gubbins.spindroid.capture.CaptureStatus
import app.gubbins.spindroid.capture.SessionApps
import app.gubbins.spindroid.mass.AutomaticPairingState
import app.gubbins.spindroid.mass.DiscoveredServer
import app.gubbins.spindroid.mass.MassAccountState
import app.gubbins.spindroid.mass.MassPlayerState
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
    keptPairings: List<PskRecord>,
    discoveredServer: DiscoveredServer?,
    discoveryError: String?,
    sessionApps: List<SessionApps.SessionApp>,
    sessionAccessMissing: Boolean,
    pickedSession: String?,
    playerPick: String?,
    onPickSession: (String) -> Unit,
    onSessionPlay: (String) -> Unit,
    onSessionPause: (String) -> Unit,
    onOpenSession: (String) -> Unit,
    onAllowSessionAccess: () -> Unit,
    sessionAccessMayBeRestricted: Boolean,
    onOpenSessionAppInfo: () -> Unit,
    onPickPlayer: (String) -> Unit,
    onDiscardPlayer: (String) -> Unit,
    onRefreshPlayers: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onCopyToken: () -> Unit,
    onOpenFinder: () -> Unit,
    onDiscard: (String) -> Unit,
    onOpenSignIn: () -> Unit,
    onSignOut: () -> Unit,
) {
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    // The loudness of the capture, as the share of a picked row that
    // the fill covers. Both cards show it on the row that carries the
    // stream, so the highlight moves with the sound.
    val fillLevel = fillLevelOf(captureStatus.peak)

    // The place of the pairing section that the onboarding option points
    // at. The scroll offset is in the same space as the position in the
    // column, so the button reaches the section without knowing its height.
    var pairingTop by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // The bar does not scroll, so the stream control is always in view.
        TopBar(
            running = running,
            connectEnabled = running || (pickedSession != null && playerPick != null),
            onConnect = onConnect,
            onDisconnect = onDisconnect,
        )

        // The picked app left its session while the stream runs. The
        // capture keeps sending silence, so the line says what happened.
        if (running && pickedSession != null && sessionApps.none { it.packageName == pickedSession }) {
            Text(
                text = stringResource(R.string.source_vanished),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
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
                        level = fillLevel,
                        missingAccess = sessionAccessMissing,
                        onAllowAccess = onAllowSessionAccess,
                        restrictedAccessPossible = sessionAccessMayBeRestricted,
                        onOpenAppInfo = onOpenSessionAppInfo,
                        onPick = onPickSession,
                        onPlay = onSessionPlay,
                        onPause = onSessionPause,
                        onOpenFinder = onOpenFinder,
                        onDiscard = onDiscard,
                        onOpen = onOpenSession,
                        modifier = Modifier.weight(1f),
                    )
                    DestinationCard(
                        server = discoveredServer,
                        error = discoveryError,
                        account = account,
                        players = players,
                        live = state.paired,
                        running = running,
                        level = fillLevel,
                        playerPick = playerPick,
                        signedIn = account.signedIn,
                        onSignIn = onOpenSignIn,
                        onPair = { scope.launch { scrollState.animateScrollTo(pairingTop) } },
                        onPick = onPickPlayer,
                        onDiscard = onDiscardPlayer,
                        onRefresh = onRefreshPlayers,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SourceCard(
                        apps = sessionApps,
                        picked = pickedSession,
                        level = fillLevel,
                        missingAccess = sessionAccessMissing,
                        onAllowAccess = onAllowSessionAccess,
                        restrictedAccessPossible = sessionAccessMayBeRestricted,
                        onOpenAppInfo = onOpenSessionAppInfo,
                        onPick = onPickSession,
                        onPlay = onSessionPlay,
                        onPause = onSessionPause,
                        onOpenFinder = onOpenFinder,
                        onDiscard = onDiscard,
                        onOpen = onOpenSession,
                    )
                    DestinationCard(
                        server = discoveredServer,
                        error = discoveryError,
                        account = account,
                        players = players,
                        live = state.paired,
                        running = running,
                        level = fillLevel,
                        playerPick = playerPick,
                        signedIn = account.signedIn,
                        onSignIn = onOpenSignIn,
                        onPair = { scope.launch { scrollState.animateScrollTo(pairingTop) } },
                        onPick = onPickPlayer,
                        onDiscard = onDiscardPlayer,
                        onRefresh = onRefreshPlayers,
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
            keptPairings = keptPairings,
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

            StatusSection(
                state = state,
                captureStatus = captureStatus,
                buildLabel = buildLabel,
            )
        }
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

/** The width from which the two cards sit side by side. */
private val WIDE_WIDTH = 600.dp
