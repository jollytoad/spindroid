package app.gubbins.spindroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gubbins.sendspin.session.SourceSessionState
import app.gubbins.spindroid.R
import app.gubbins.spindroid.capture.CaptureStatus
import app.gubbins.spindroid.capture.SessionApps
import app.gubbins.spindroid.mass.AutomaticPairingState
import app.gubbins.spindroid.mass.DiscoveredServer
import app.gubbins.spindroid.mass.MassAccountState
import app.gubbins.spindroid.mass.MassPlayerState

/**
 * The screen of the app.
 *
 * Two cards carry the routing: the Source card lists the apps that hold a
 * media session, and the Destination card lists the players that can play
 * the phone. The Connect control in the bar streams the picked source to
 * the picked destination. The status stays below, because it serves the
 * diagnosis.
 */
@Composable
fun MainScreen(
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
    onOpenFinder: () -> Unit,
    onDiscard: (String) -> Unit,
    onOpenSignIn: () -> Unit,
    onSignOut: () -> Unit,
) {
    val scrollState = rememberScrollState()

    // The loudness of the capture, as the share of a picked row that
    // the fill covers. Both cards show it on the row that carries the
    // stream, so the highlight moves with the sound.
    val fillLevel = fillLevelOf(captureStatus.peak)

    // The pairing runs on its own after the sign in, so its failure
    // arrives as an event: the snackbar tells it once, and the status
    // below keeps the state.
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(automaticPairing) {
        val failure = automaticPairing as? AutomaticPairingState.Failed
        if (failure != null) {
            snackbar.showSnackbar(context.getString(R.string.pairing_failed, failure.reason))
        }
    }

    Box(modifier = Modifier.fillMaxWidth()) {
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
                                onSignOut = onSignOut,
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
                                onSignOut = onSignOut,
                                onPick = onPickPlayer,
                                onDiscard = onDiscardPlayer,
                                onRefresh = onRefreshPlayers,
                            )
                        }
                    }
                }

                StatusSection(
                    state = state,
                    captureStatus = captureStatus,
                    buildLabel = buildLabel,
                )
            }
        }

        // The pairing failure reads at the bottom, over the cards.
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
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
