package com.sendspin.sender.capture

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The stub that receives the media sessions.
 *
 * The system binds to a notification listener and hands it the media
 * sessions of the device, so the stub needs no code of its own. The user
 * grants the access once, under the notification access settings, and the
 * grant is what keeps the session reading private.
 */
class PlayingAppsListener : NotificationListenerService()

/**
 * The apps that hold a media session on the phone.
 *
 * The Source card lists them, and the pick chooses the one app whose
 * audio the capture should follow. A session that stands paused keeps its
 * metadata, so the list holds paused apps too, and a pick stays good
 * while the app is paused. The list updates itself through the session
 * listener and the controller callbacks, so no refresh button exists.
 */
object SessionApps {

    /** One app that holds a media session. */
    data class SessionApp(
        val packageName: String,
        val label: String,
        /** The uid that the capture filter takes. */
        val uid: Int,
        /** Whether the session plays right now. A pause keeps the session. */
        val playingNow: Boolean,
        /** The now-playing line of the session, when the app publishes one. */
        val nowPlaying: String?,
        /** Whether the session honours the play command right now. */
        val canPlay: Boolean,
        /** Whether the session honours the pause command right now. */
        val canPause: Boolean,
    )

    private val mutableApps = MutableStateFlow<List<SessionApp>>(emptyList())

    /** The apps that hold a session, playing ones first. */
    val apps: StateFlow<List<SessionApp>> = mutableApps.asStateFlow()

    private val mutablePicked = MutableStateFlow<String?>(null)

    /** The package of the app the user picked, or null before a pick. */
    val picked: StateFlow<String?> = mutablePicked.asStateFlow()

    /** Whether the picked package is still among the listed apps. */
    fun pickedIsLive(): Boolean {
        val picked = mutablePicked.value ?: return false
        return mutableApps.value.any { it.packageName == picked }
    }

    /**
     * The app that the pick follows to, or null while the pick stands.
     *
     * The phone plays one media app at a time, because the well-behaved
     * apps agree over the audio focus about which one it is. The pick
     * follows the focus: while the picked app stands paused and exactly
     * one other app plays, that app takes the pick over. The one-app
     * count keeps the pick still, because two apps fighting for the focus
     * would otherwise bounce the pick, and each bounce moves the capture.
     * A vanish does not move the pick: it has its own status line, and a
     * gone session must not hand the stream to another app by accident.
     */
    fun followTarget(picked: String?, apps: List<SessionApp>): String? {
        if (picked == null) return null
        val pickedApp = apps.firstOrNull { it.packageName == picked } ?: return null
        if (pickedApp.playingNow) return null
        val playing = apps
            .filter { it.playingNow && it.packageName != picked }
        return playing.singleOrNull()?.packageName
    }

    private var context: Context? = null

    /**
     * The controllers that carry a callback, by package. The token rides
     * along: a recreated session carries a new token, and the registration
     * follows it. Each registration replays its current state, so a read
     * that finds no change must register nothing.
     */
    private val withCallbacks =
        mutableMapOf<String, Pair<MediaController, MediaSession.Token>>()
    private var manager: MediaSessionManager? = null
    private var listener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private val handlers = mutableMapOf<String, MediaController>()

    // The handler belongs to the main looper of the device, so it is made
    // on the first use: a JVM unit test reads the pure rules without an
    // Android looper.
    private val handler: Handler by lazy { Handler(Looper.getMainLooper()) }
    private var retryPending = false

    /**
     * Starts the session reading.
     *
     * The registration fails with a security exception when the user has
     * not granted the notification listener access yet, so the reading
     * retires itself until the grant arrives.
     */
    fun start(appContext: Context) {
        stop()
        context = appContext
        manager = appContext.getSystemService(MediaSessionManager::class.java)
        val changed = MediaSessionManager.OnActiveSessionsChangedListener {
            read(appContext)
        }
        try {
            manager?.addOnActiveSessionsChangedListener(changed, ComponentName(appContext, PlayingAppsListener::class.java), handler)
        } catch (exception: SecurityException) {
            Log.i(TAG, "The media sessions are not readable, because the access is missing")
            scheduleRetry(appContext, changed)
            return
        }
        listener = changed
        read(appContext)
    }

    /**
     * The uid that the capture filter takes for one package.
     *
     * The answer is 0 when the app is not in the list, either because it
     * has no session or because the list is stale at the call.
     */
    fun uidOf(packageName: String?): Int =
        apps.value.firstOrNull { it.packageName == packageName }?.uid ?: 0

    /** Stops the session reading and forgets the list. */
    fun stop() {
        manager?.let { sessionManager ->
            listener?.let { sessionManager.removeOnActiveSessionsChangedListener(it) }
            withCallbacks.values.forEach { it.first.unregisterCallback(callback) }
        }
        manager = null
        listener = null
        withCallbacks.clear()
        handlers.clear()
        handler.removeCallbacksAndMessages(null)
        retryPending = false
        mutableApps.value = emptyList()
        context = null
    }

    /** Picks the app the capture should follow, by package name. */
    fun pick(packageName: String) {
        mutablePicked.value = packageName
    }

    /** Sends play to the picked app's session. */
    fun play(packageName: String) {
        handlers[packageName]?.transportControls?.play()
    }

    /** Sends pause to the picked app's session. */
    fun pause(packageName: String) {
        handlers[packageName]?.transportControls?.pause()
    }

    /**
     * The callback that keeps the rows fresh between session changes.
     *
     * A session change moves the list, but a play, a pause, or a new
     * metadata title arrives here, so the rows re-read on them too.
     */
    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            reread()
        }

        override fun onMetadataChanged(metadata: android.media.MediaMetadata?) {
            reread()
        }
    }

    /** Reads the sessions again on the next loop pass. */
    private fun reread() {
        val appContext = context ?: return
        handler.post { read(appContext) }
    }

    /**
     * Reads the sessions of the device, and keeps one controller per app.
     *
     * The list holds every session that publishes metadata, including a
     * session of the app itself if one existed. In normal use the sender
     * holds none, and the BDD scenarios need their own session, which the
     * instrumentation creates in the app process.
     */
    private fun read(appContext: Context) {
        val sessionManager = manager ?: return
        val sessions = try {
            sessionManager.getActiveSessions(ComponentName(appContext, PlayingAppsListener::class.java))
        } catch (exception: SecurityException) {
            Log.i(TAG, "The media sessions are not readable, because the access is missing")
            scheduleRetry(appContext, null)
            return
        }
        val fresh = mutableMapOf<String, MediaController>()
        val currentPackages = mutableSetOf<String>()
        val list = sessions
            .filter { it.metadata != null }
            .distinctBy { it.packageName }
            .sortedByDescending { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            .mapNotNull { controller ->
                fresh[controller.packageName] = controller
                currentPackages += controller.packageName
                // A session that the app rebuilt carries a new token, so
                // the callbacks follow tokens, never objects: an old
                // controller that lost its session goes quiet and would
                // leave the transport buttons stale.
                val token = controller.sessionToken
                val registered = withCallbacks[controller.packageName]
                if (registered == null || registered.second != token) {
                    registered?.first?.unregisterCallback(callback)
                    controller.registerCallback(callback)
                    withCallbacks[controller.packageName] = controller to token
                }
                appOf(appContext, controller)
            }
        withCallbacks.keys.filterNot { it in currentPackages }.forEach { gone ->
            withCallbacks.remove(gone)?.first?.unregisterCallback(callback)
        }
        handlers.clear()
        handlers.putAll(fresh)
        mutableApps.value = list
    }

    /** One row of the list, or null when the app cannot be named. */
    private fun appOf(appContext: Context, controller: MediaController): SessionApp? {
        val packageName = controller.packageName
        return try {
            val info = appContext.packageManager.getApplicationInfo(packageName, 0)
            val uid = appContext.packageManager.getPackageUid(packageName, 0)
            if (uid <= 0) return null
            val actions = controller.playbackState?.actions ?: 0L
            // The mask that Spotify uses: some apps publish only the
            // play-pause action, and the pair of buttons must read it as
            // the cover of both.
            val plays = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PLAY_PAUSE
            val pauses = PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE
            SessionApp(
                packageName = packageName,
                label = appContext.packageManager.getApplicationLabel(info).toString(),
                uid = uid,
                playingNow = controller.playbackState?.state == PlaybackState.STATE_PLAYING,
                nowPlaying = controller.metadata?.description?.toString(),
                canPlay = actions and plays != 0L,
                canPause = actions and pauses != 0L,
            )
        } catch (exception: android.content.pm.PackageManager.NameNotFoundException) {
            null
        }
    }

    /**
     * Retires the read until the grant arrives.
     *
     * The user grants the access in the settings, so the app keeps
     * watching for it instead of giving up. The retry stops when the app
     * closes or when a session change arrives first.
     */
    private fun scheduleRetry(appContext: Context, changed: MediaSessionManager.OnActiveSessionsChangedListener?) {
        val sessionManager = manager ?: return
        if (retryPending) return
        retryPending = true
        handler.postDelayed({
            retryPending = false
            if (context == null) return@postDelayed
            if (changed != null) {
                try {
                    sessionManager.removeOnActiveSessionsChangedListener(changed)
                    sessionManager.addOnActiveSessionsChangedListener(
                        changed,
                        ComponentName(appContext, PlayingAppsListener::class.java),
                        handler,
                    )
                } catch (exception: SecurityException) {
                    scheduleRetry(appContext, changed)
                    return@postDelayed
                }
            }
            listener = changed
            read(appContext)
        }, RETRY_MILLIS)
    }

    private const val TAG = "SendSpin"
    private const val RETRY_MILLIS = 2_000L
}
