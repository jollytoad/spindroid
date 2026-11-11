package com.sendspin.sender

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.sendspin.protocol.crypto.X25519
import com.sendspin.protocol.pairing.PairingToken
import com.sendspin.sender.capture.CaptureStatusHolder
import com.sendspin.sender.mass.MassAccountManager
import com.sendspin.sender.mass.MassDiscovery
import com.sendspin.sender.mass.MassPlayerManager
import com.sendspin.sender.service.SendSpinService
import com.sendspin.sender.session.SessionStateHolder
import com.sendspin.sender.storage.EncryptedKeyStore
import com.sendspin.sender.ui.MainScreen
import kotlinx.coroutines.launch
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
    private lateinit var discovery: MassDiscovery
    private lateinit var players: MassPlayerManager

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

        setContent {
            val state by SessionStateHolder.state.collectAsStateWithLifecycle()
            val serviceRuns by SessionStateHolder.running.collectAsStateWithLifecycle()
            val captureStatus by CaptureStatusHolder.state.collectAsStateWithLifecycle()
            val account by accounts.state.collectAsStateWithLifecycle()
            val playerList by players.state.collectAsStateWithLifecycle()

            // The list of players comes from the server, so it is read once
            // the account is signed in. It is read again when the phone is
            // paired, because the server only lists the phone while it is
            // connected.
            LaunchedEffect(account.signedIn, state.paired) {
                if (account.signedIn) players.refresh()
            }

            MainScreen(
                pairingToken = pairingToken(),
                state = state,
                captureStatus = captureStatus,
                buildLabel = buildLabel(),
                running = serviceRuns,
                message = message,
                account = account,
                players = playerList,
                sourceUri = players.sourceUri,
                clientId = players.clientId,
                onConnect = { connect() },
                onDisconnect = { disconnect() },
                onCopyToken = { copyToken() },
                onSignIn = { serverUrl, username, password ->
                    accounts.signIn(serverUrl, username, password)
                },
                onSignOut = { accounts.signOut() },
                onServerUrlChange = { serverUrl ->
                    accounts.setServerUrl(serverUrl, edited = true)
                },
                onRefreshPlayers = { players.refresh() },
                onChoosePlayer = { playerId -> players.choose(playerId) },
            )
        }

        // The address of the server is found over the network, so the search
        // runs on its own thread.
        lifecycleScope.launch {
            discovery.discover(
                onFound = { serverUrl -> accounts.setServerUrl(serverUrl, edited = false) },
                onFailed = { reason -> message = reason },
            )
        }
    }

    override fun onDestroy() {
        discovery.stop()
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
        SendSpinService.start(
            context = this,
            resultCode = resultCode,
            resultData = data,
        )
    }

    /** Stops the service. */
    private fun disconnect() {
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
