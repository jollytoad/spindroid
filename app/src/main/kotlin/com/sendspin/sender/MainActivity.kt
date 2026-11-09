package com.sendspin.sender

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sendspin.protocol.crypto.X25519
import com.sendspin.protocol.pairing.PairingToken
import com.sendspin.sender.service.SendSpinService
import com.sendspin.sender.session.SessionStateHolder
import com.sendspin.sender.storage.CaptureSource
import com.sendspin.sender.storage.EncryptedKeyStore
import com.sendspin.sender.storage.Settings
import com.sendspin.sender.ui.MainScreen

/**
 * The only screen of the app.
 *
 * It shows the server address, the capture source, the pairing token, and
 * the state of the connection.
 */
class MainActivity : ComponentActivity() {
    private lateinit var settings: Settings
    private lateinit var keys: EncryptedKeyStore

    /** The message the app shows under the fields. */
    private var message by mutableStateOf<String?>(null)

    /** Whether the service is running. */
    private var running by mutableStateOf(false)

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

    /** The microphone permission, which the capture needs. */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            message = getString(R.string.error_microphone_permission)
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
        settings = Settings(this)
        keys = EncryptedKeyStore(this)

        setContent {
            val state by SessionStateHolder.state.collectAsStateWithLifecycle()
            MainScreen(
                serverAddress = settings.serverAddress,
                captureSource = settings.captureSource,
                direction = settings.direction,
                pairingToken = pairingToken(),
                state = state,
                running = running,
                message = message,
                onServerAddressChange = { settings.serverAddress = it },
                onCaptureSourceChange = { settings.captureSource = it },
                onDirectionChange = { settings.direction = it },
                onConnect = { connect() },
                onDisconnect = { disconnect() },
                onCopyToken = { copyToken() },
            )
        }
    }

    /**
     * Starts the capture.
     *
     * The system audio needs the consent of the media projection, so the
     * app asks for it first. The microphone needs a permission, which the
     * app asks for at the same time as the notifications.
     */
    private fun connect() {
        pendingStep = ::askForMicrophone
        askFor(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Asks for the microphone, and then starts the capture. */
    private fun askForMicrophone() {
        pendingStep = ::startCaptureWithConsent
        askFor(Manifest.permission.RECORD_AUDIO)
    }

    /**
     * Starts the capture, asking for the consent of the media projection
     * first when the system audio is the source.
     */
    private fun startCaptureWithConsent() {
        if (settings.captureSource != CaptureSource.SYSTEM_AUDIO) {
            startCapture(0, null)
            return
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    /**
     * Asks for a permission, and carries on whether it was granted.
     *
     * The microphone capture fails without its permission, but the app
     * still starts, so the user sees why nothing happens.
     */
    private fun askFor(permission: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            onPermissionAnswered()
            return
        }
        when (permission) {
            Manifest.permission.POST_NOTIFICATIONS -> notificationLauncher.launch(permission)
            else -> permissionLauncher.launch(permission)
        }
    }

    /** Starts the service, with the consent of the media projection. */
    private fun startCapture(resultCode: Int, data: Intent?) {
        SendSpinService.start(
            context = this,
            source = settings.captureSource,
            resultCode = resultCode,
            resultData = data,
        )
        running = true
    }

    /** Stops the service. */
    private fun disconnect() {
        stopService(Intent(this, SendSpinService::class.java))
        running = false
    }

    /** The token the operator pastes into Music Assistant. */
    private fun pairingToken(): String = PairingToken.encodePairingPsk(
        X25519.publicKey(keys.staticPrivateKey),
        keys.pairingPsk,
    )

    /** Copies the pairing token to the clipboard. */
    private fun copyToken() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("SendSpin", pairingToken()))
        message = getString(R.string.action_copy_done)
    }
}
