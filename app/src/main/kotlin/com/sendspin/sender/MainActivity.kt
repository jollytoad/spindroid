package com.sendspin.sender

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.util.Log
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sendspin.protocol.crypto.X25519
import com.sendspin.protocol.pairing.PairingToken
import com.sendspin.sender.capture.CaptureStatusHolder
import com.sendspin.sender.capture.SessionApps
import com.sendspin.sender.mass.AutomaticPairing
import com.sendspin.sender.mass.MassAccountManager
import com.sendspin.sender.mass.MassDiscovery
import com.sendspin.sender.mass.MassPlayerManager
import com.sendspin.sender.mass.MusicAssistantDiscovery
import com.sendspin.sender.service.SendSpinService
import com.sendspin.sender.session.SessionStateHolder
import com.sendspin.sender.storage.EncryptedKeyStore
import com.sendspin.sender.ui.MainScreen
import com.sendspin.sender.ui.SignInScreen
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.text.DateFormat
import java.util.Date

/**
 * The only screen of the app.
 *
 * It shows the button that starts the capture, the pairing token, and the
 * state of the connection.
 */
class MainActivity : ComponentActivity() {
    private lateinit var keys: EncryptedKeyStore
    private lateinit var accounts: MassAccountManager
    private lateinit var discovery: MusicAssistantDiscovery
    private lateinit var players: MassPlayerManager
    private lateinit var pairing: AutomaticPairing

    private companion object {
        const val TAG = "SendSpin"

        /** How often the app tries to start the picked player. */
        const val ROUTE_ATTEMPTS = 10
    }

    /** The message the app shows under the fields. */
    private var message by mutableStateOf<String?>(null)

    /**
     * The consent of the media projection.
     *
     * The service needs the consent, so the capture waits for the user to
     * allow it.
     */
    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode != RESULT_OK || data == null) {
            message = getString(R.string.error_projection_denied)
            return@registerForActivityResult
        }
        startCapture(result.resultCode, data)
    }

    /** The notification permission, which the service needs to show its notice. */
    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            message = getString(R.string.error_notifications_permission)
        }
        onPermissionAnswered()
    }

    /** The record permission, which the capture of the system audio needs. */
    private val recordPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            message = getString(R.string.error_record_permission)
        }
        onPermissionAnswered()
    }

    /**
     * What to do once the permissions are settled.
     *
     * The app asks for the permissions one after the other, and then starts
     * the capture.
     */
    private var pendingStep: (() -> Unit)? = null

    /** Runs the step that waits for the permissions. */
    private fun onPermissionAnswered() {
        val step = pendingStep
        pendingStep = null
        step?.invoke()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keys = EncryptedKeyStore(this)
        accounts = MassAccountManager(this)
        discovery = MassDiscovery(this)
        players = MassPlayerManager(this, accounts)
        pairing = AutomaticPairing(this, accounts, players.clientId)
        pairing.start()

        setContent {
            val state by SessionStateHolder.state.collectAsStateWithLifecycle()
            val serviceRuns by SessionStateHolder.running.collectAsStateWithLifecycle()
            val captureStatus by CaptureStatusHolder.state.collectAsStateWithLifecycle()
            val account by accounts.state.collectAsStateWithLifecycle()
            val automaticPairing by pairing.state.collectAsStateWithLifecycle()
            val playerList by players.state.collectAsStateWithLifecycle()
            val foundServer by discovery.server.collectAsStateWithLifecycle()
            val discoveryError by discovery.error.collectAsStateWithLifecycle()

            val sessionApps by SessionApps.apps.collectAsStateWithLifecycle()
            val pickedSession by SessionApps.picked.collectAsStateWithLifecycle()

            // The pairings that the phone keeps, read once here and again
            // whenever a pairing could have arrived or gone.
            var keptPairings by mutableStateOf(keys.longTermRecords())
            LaunchedEffect(state.paired, automaticPairing) {
                keptPairings = keys.longTermRecords()
            }

            // The sessions come from the media sessions of the device, so
            // the reader starts with the screen and stops with it.
            LaunchedEffect(Unit) { SessionApps.start(applicationContext) }
            DisposableEffect(Unit) {
                onDispose { SessionApps.stop() }
            }

            // The pick follows the focus: the phone plays one media app at
            // a time, and the stream must carry the sound that plays. The
            // swap reuses the running projection, so the move costs no
            // consent and no new pick tap.
            LaunchedEffect(Unit) {
                SessionApps.apps.collect { apps ->
                    val target = SessionApps.followTarget(SessionApps.picked.value, apps)
                    if (target != null) pickSource(target)
                }
            }

            // The pick of the player is a choice of the screen, not of the
            // server. The server learns it when the Connect control starts.
            var playerPick by rememberSaveable { mutableStateOf<String?>(null) }

            // The sign in form lives on a page of its own. The page closes
            // when the sign in works, and the main page shows the account
            // then.
            var signInPage by rememberSaveable { mutableStateOf(false) }

            // The address of the server comes from the search, so a sign in
            // works without the user typing it. A typed address still wins.
            LaunchedEffect(foundServer) {
                foundServer?.let { accounts.setServerUrl(it.url, edited = false) }
            }

            LaunchedEffect(account.signedIn) {
                if (account.signedIn) signInPage = false
            }

            // The list of players comes from the server, so it is read once
            // the account is signed in. It is read again when the phone is
            // paired, because the server only lists the phone while it is
            // connected.
            LaunchedEffect(account.signedIn, state.paired) {
                if (account.signedIn) players.refresh()
            }

            if (signInPage) {
                SignInScreen(
                    account = account,
                    onSignIn = { serverUrl, username, password ->
                        accounts.signIn(serverUrl, username, password)
                    },
                    onCancel = { signInPage = false },
                    onServerUrlChange = { serverUrl ->
                        accounts.setServerUrl(serverUrl, edited = true)
                    },
                )
            } else {
                MainScreen(
                    pairingToken = pairingToken(),
                    state = state,
                    captureStatus = captureStatus,
                    buildLabel = buildLabel(),
                    running = serviceRuns,
                    message = message,
                    account = account,
                    players = playerList,
                    automaticPairing = automaticPairing,
                    keptPairings = keptPairings,
                    discoveredServer = foundServer,
                    discoveryError = discoveryError,
                    sessionApps = sessionApps,
                    pickedSession = pickedSession,
                    playerPick = playerPick,
                    onPickSession = { packageName -> pickSource(packageName) },
                    onSessionPlay = { packageName -> SessionApps.play(packageName) },
                    onSessionPause = { packageName -> SessionApps.pause(packageName) },
                    onPickPlayer = { playerId ->
                        playerPick = playerId
                        players.startRouting(playerId)
                    },

                    onConnect = { connect() },
                    onDisconnect = { disconnect() },
                    onCopyToken = { copyToken() },
                    onOpenSignIn = { signInPage = true },
                    onSignOut = {
                        pairing.reset()
                        accounts.signOut()
                        playerPick = null
                    },
                )
            }
        }

        // The address of the server is found over the network, so the search
        // runs on its own thread.
        discovery.start()
    }

    override fun onDestroy() {
        discovery.stop()
        pairing.close()
        players.close()
        accounts.close()
        super.onDestroy()
    }


    /**
     * Starts the capture.
     *
     * The system audio needs the consent of the media projection, so the
     * app asks for it first. The capture of the system audio needs the
     * record permission, and the service needs the notification permission.
     */
    /**
     * Picks the app that the capture follows.
     *
     * While the stream runs, the pick swaps the capture filter of the
     * running projection on its own, and no consent dialog appears,
     * because the projection carries the whole screen and only the filter
     * changes. When the picked session is not among the read sessions, the
     * stream keeps its source, and the line under the bar says so.
     *
     * @param packageName the package of the picked app
     */
    private fun pickSource(packageName: String) {
        SessionApps.pick(packageName)
        if (!SessionStateHolder.running.value) return
        val uid = SessionApps.uidOf(packageName)
        if (uid > 0) {
            SendSpinService.switchSource(this, intArrayOf(uid))
        } else {
            message = getString(R.string.source_gone)
        }
    }

    private fun connect() {
        pendingStep = ::askForRecordPermission
        askFor(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Asks for the record permission, and then for the consent. */
    private fun askForRecordPermission() {
        pendingStep = ::askForProjectionConsent
        askFor(Manifest.permission.RECORD_AUDIO)
    }

    /** Asks the user for the consent of the media projection. */
    private fun askForProjectionConsent() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    /**
     * Asks for a permission, and carries on whether it was granted.
     *
     * The capture fails without the record permission, but the app still
     * starts, so the user sees why nothing happens.
     */
    private fun askFor(permission: String) {
        when (permission) {
            Manifest.permission.POST_NOTIFICATIONS -> notificationLauncher.launch(permission)
            else -> recordPermissionLauncher.launch(permission)
        }
    }

    /** Starts the service, with the consent of the media projection. */
    private fun startCapture(resultCode: Int, data: Intent?) {
        val uid = SessionApps.uidOf(SessionApps.picked.value)
        SendSpinService.start(
            context = this,
            resultCode = resultCode,
            resultData = data,
            uids = if (uid > 0) intArrayOf(uid) else null,
        )
    }

    /** Stops the service. */
    private fun disconnect() {
        // The player that plays the phone goes quiet on its own source, so
        // the stream does not carry on there after the phone disconnects.
        players.stopRouting()
        stopService(Intent(this, SendSpinService::class.java))
    }

    /** The token the operator pastes into Music Assistant. */
    private fun pairingToken(): String = PairingToken.encodePairingPsk(
        X25519.publicKey(keys.staticPrivateKey),
        keys.pairingPsk,
    )

    /**
     * The version and the time of the build on the phone.
     *
     * The time is the moment the package was installed. It tells the builds
     * apart, because the version number does not change.
     */
    private fun buildLabel(): String {
        val info = packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        val format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        return "${info.versionName} · ${format.format(Date(info.lastUpdateTime))}"
    }

    /** Copies the pairing token to the clipboard. */
    private fun copyToken() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("SendSpin", pairingToken()))
        message = getString(R.string.action_copy_done)
    }
}
