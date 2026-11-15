package com.sendspin.sender.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.sendspin.sender.MainActivity
import com.sendspin.sender.R
import com.sendspin.sender.capture.AudioCapture
import com.sendspin.sender.capture.CaptureStatusHolder
import com.sendspin.sender.capture.SilentCapture
import com.sendspin.sender.capture.SystemAudioCapture
import com.sendspin.sender.discovery.ServiceAdvertiser
import com.sendspin.sender.net.InboundServer
import com.sendspin.sender.session.PairingServiceHolder
import com.sendspin.sender.session.SessionStateHolder
import com.sendspin.sender.storage.EncryptedKeyStore
import com.sendspin.sender.storage.Settings

/**
 * The service that keeps the app alive while it streams.
 *
 * Android puts the media projection behind a foreground service, so the
 * service holds the capture and the server that waits for the Music
 * Assistant server to dial in. The service starts before the media
 * projection is opened, because Android 14 wants the service in place
 * first.
 */
class SendSpinService : Service() {
    private lateinit var settings: Settings
    private lateinit var keys: EncryptedKeyStore
    private var inboundServer: InboundServer? = null
    private var advertiser: ServiceAdvertiser? = null
    private var capture: AudioCapture? = null
    private var mediaProjection: MediaProjection? = null

    /** Whether the service listens only, so a server can pair the phone. */
    private var pairing = false

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        keys = EncryptedKeyStore(this)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                return START_NOT_STICKY
            }

            ACTION_PAIR -> startPairingListener()
            else -> startEverything(intent)
        }
        // The capture needs the permission and the media projection consent
        // of this run, so a restart without them would fail. The user starts
        // the service again instead.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }

    /**
     * Says that the service runs.
     *
     * The screen shows a disconnect button instead of a connect button
     * while the service runs, so it has to know.
     */
    private fun publishRunning(isRunning: Boolean) {
        SessionStateHolder.publishRunning(isRunning)
    }

    /**
     * Starts the capture and the server.
     *
     * The service has to be in the foreground before the media projection
     * is opened, so the notification goes up first.
     */
    private fun startEverything(intent: Intent?) {
        if (inboundServer != null) return
        pairing = false
        startForegroundWithType(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        publishRunning(true)

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultCode == 0 || data == null) {
            Log.e(TAG, "The media projection has no consent")
            stopSelf()
            return
        }
        val projection = getSystemService(MediaProjectionManager::class.java)
            .getMediaProjection(resultCode, data)
        if (projection == null) {
            Log.e(TAG, "The media projection could not be opened")
            stopSelf()
            return
        }
        mediaProjection = projection
        val captureSource = SystemAudioCapture(projection)
        capture = captureSource
        CaptureStatusHolder.publishRunning(true)
        CaptureStatusHolder.publishError(null)

        startListening(captureSource)
    }

    /**
     * Starts only the listener and the advertisement.
     *
     * The pairing runs before the user allows the screen capture. Music
     * Assistant has to find the phone and connect to it for the pairing, so
     * the socket and the advertisement run without a capture. A silent
     * capture keeps the listener's contract.
     */
    private fun startPairingListener() {
        if (inboundServer != null) return
        pairing = true
        PairingServiceHolder.publishError(null)
        startForegroundWithType(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        startListening(SilentCapture())
    }

    /**
     * Starts the server that waits for Music Assistant.
     *
     * The advertisement starts only once the socket listens. A server that
     * reads the advertisement and dials at once would otherwise find a
     * closed port.
     *
     * @param captureSource where the audio comes from, or a silent capture
     *   when the service only pairs
     */
    private fun startListening(captureSource: AudioCapture) {
        SessionStateHolder.reset()
        val inbound = InboundServer(
            keys = keys,
            suite = settings.cipherSuite,
            port = ServiceAdvertiser.PORT,
            format = InboundServer.FORMAT,
            capture = captureSource,
            listener = object : InboundServer.Listener {
                override fun onStateChanged(state: com.sendspin.protocol.session.SourceSessionState) {
                    SessionStateHolder.publish(state)
                    updateNotification(state)
                }

                override fun onError(message: String) {
                    Log.e(TAG, "The incoming connection failed: $message")
                    // The status panel shows this, so the user knows why the
                    // phone waits for a server that never comes.
                    CaptureStatusHolder.publishError(message)
                    if (pairing) PairingServiceHolder.publishError(message)
                }
            },
        )
        inboundServer = inbound
        inbound.start {
            if (inboundServer !== inbound) return@start
            val advertiser = ServiceAdvertiser(
                this,
                InboundServer.DEVICE_NAME,
                ServiceAdvertiser.PORT,
            )
            advertiser.start(
                onRegistered = {
                    // The pairing manager waits for the advertisement, because
                    // Music Assistant cannot find the phone before it runs.
                    if (pairing) PairingServiceHolder.publishRunning(true)
                },
                onFailed = { message ->
                    Log.e(TAG, "The advertisement failed: $message")
                    if (pairing) PairingServiceHolder.publishError(message)
                },
            )
            this.advertiser = advertiser
        }
    }

    /** Stops the capture and the server. */
    private fun stopEverything() {
        advertiser?.stop()
        advertiser = null
        inboundServer?.stop()
        inboundServer = null
        capture?.stop()
        capture = null
        CaptureStatusHolder.reset()
        mediaProjection?.stop()
        mediaProjection = null
        pairing = false
        PairingServiceHolder.reset()
        publishRunning(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Puts the service in the foreground, with the type the capture needs. */
    private fun startForegroundWithType(type: Int) {
        startForeground(NOTIFICATION_ID, buildNotification(), type)
    }

    /** Updates the notification, so it shows the current state. */
    private fun updateNotification(state: com.sendspin.protocol.session.SourceSessionState?) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(state))
    }

    /** Builds the notification, which shows the state and offers a stop button. */
    private fun buildNotification(state: com.sendspin.protocol.session.SourceSessionState? = null): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, SendSpinService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = when {
            pairing -> getString(R.string.notification_pairing)
            state == null -> getString(R.string.notification_idle)
            state.streaming -> getString(R.string.notification_streaming)
            state.serverRequestedAudio -> getString(R.string.notification_waiting)
            state.phase == com.sendspin.protocol.session.SessionPhase.CLOSED ->
                getString(R.string.notification_disconnected)
            else -> getString(R.string.notification_connecting)
        }
        val text = when {
            pairing -> getString(R.string.notification_pairing_text)
            else -> state?.serverName ?: getString(R.string.notification_text)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notification_stop), stop)
            .build()
    }

    /** Creates the notification channel. */
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.setShowBadge(false)
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "SendSpin"
        private const val CHANNEL_ID = "sendspin_capture"
        private const val NOTIFICATION_ID = 1

        /** The action that stops the service. */
        const val ACTION_STOP = "com.sendspin.sender.STOP"

        /** The action that listens without a capture, so a server can pair the phone. */
        const val ACTION_PAIR = "com.sendspin.sender.PAIR"

        /** The extra that holds the consent result of the media projection. */
        const val EXTRA_RESULT_CODE = "result_code"

        /** The extra that holds the consent data of the media projection. */
        const val EXTRA_RESULT_DATA = "result_data"

        /**
         * Starts the listener without a capture.
         *
         * Music Assistant has to connect to the phone before it can pair
         * it. The listener therefore starts before the user allows the
         * screen capture.
         *
         * @param context the context that starts the service
         */
        fun startPairing(context: Context) {
            val intent = Intent(context, SendSpinService::class.java).setAction(ACTION_PAIR)
            context.startForegroundService(intent)
        }

        /**
         * Starts the service.
         *
         * @param context the context that starts the service
         * @param resultCode the consent result, for the media projection
         * @param resultData the consent data, for the media projection
         */
        fun start(
            context: Context,
            resultCode: Int = 0,
            resultData: Intent? = null,
        ) {
            val intent = Intent(context, SendSpinService::class.java)
            if (resultCode != 0 && resultData != null) {
                intent.putExtra(EXTRA_RESULT_CODE, resultCode)
                intent.putExtra(EXTRA_RESULT_DATA, resultData)
            }
            context.startForegroundService(intent)
        }
    }
}
