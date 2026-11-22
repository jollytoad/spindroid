package app.gubbins.spindroid.capture

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
 * sessions of the device, so the stub needs no state of its own. The user
 * grants the access once, under the notification access settings, and the
 * grant is what keeps the session reading private. The binding is also the
 * first moment the reading can succeed, so the stub tells the reader to
 * look again.
 */
class PlayingAppsListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        SessionApps.listenerConnected()
    }
}

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

    /** One app that holds a media session, or that the finder found. */
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
        /** Whether the app holds a session, or only answered the finder. */
        val holdsSession: Boolean = true,
    )

    // The sessions the app holds right now, before the finder offers join.
    private val mutableSessions = MutableStateFlow<List<SessionApp>>(emptyList())

    // The offers the finder found, and the packages a swipe hid from it.
    private val mutableOffers = MutableStateFlow<List<SessionApp>>(emptyList())
    private val mutableDiscarded = MutableStateFlow<Set<String>>(emptySet())

    /** What the Source card shows: the sessions and the finder offers. */
    val apps: StateFlow<List<SessionApp>>
        get() = mutableDisplay.asStateFlow()

    private val mutableDisplay = MutableStateFlow<List<SessionApp>>(emptyList())

    // The reading found no grant, so no app can appear. The card says so,
    // instead of showing nothing.
    private val mutableAccessMissing = MutableStateFlow(false)

    /**
     * Whether the notification listener access is missing.
     *
     * The Source card tells the user to allow it, because nobody can tell
     * an empty card of a phone at rest from a card that the missing grant
     * keeps empty.
     */
    val accessMissing: StateFlow<Boolean> = mutableAccessMissing.asStateFlow()

    /** Computes what the card shows from the sessions, the offers, the discards. */
    private fun refreshDisplay() {
        mutableDisplay.value = visibleApps(mutableSessions.value, mutableOffers.value, mutableDiscarded.value)
    }

    private val mutablePicked = MutableStateFlow<String?>(null)

    /** The package of the app the user picked, or null before a pick. */
    val picked: StateFlow<String?> = mutablePicked.asStateFlow()

    /** Whether the picked package is still among the listed apps. */
    fun pickedIsLive(): Boolean {
        val picked = mutablePicked.value ?: return false
        return mutableDisplay.value.any { it.packageName == picked }
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
     * The finder: the question the show-all control asks, and its answer.
     *
     * The open of the app asks the question on its own, and the show-all
     * control of the Source card asks it again. A test stands in for the
     * probe, so a BDD scenario names a known list of apps instead of
     * reading the device it runs on.
     */
    fun interface MediaAppFinder {
        fun installedMediaApps(): List<SessionApp>
    }

    private var finder: MediaAppFinder? = null

    /**
     * Offers the apps the user asked the finder for.
     *
     * The step says the apps are installed, so the probe is replaced with
     * the answer it should give. The finder tap then reads this answer.
     */
    fun offerInstalled(vararg labels: String) {
        finder = MediaAppFinder {
            labels.mapIndexed { index, label ->
                SessionApp(
                    packageName = label.lowercase(),
                    label = label,
                    uid = OFFER_UID_BASE + index,
                    playingNow = false,
                    nowPlaying = null,
                    canPlay = false,
                    canPause = false,
                    holdsSession = false,
                )
            }
        }
    }

    /**
     * Offers one installed app under its real package name, so the
     * icon on its row opens the app the way the launcher would, and
     * a tap that must not open it says so on a real package.
     */
    fun offerInstalledApp(label: String, packageName: String, uid: Int) {
        finder = MediaAppFinder {
            listOf(
                SessionApp(
                    packageName = packageName,
                    label = label,
                    uid = uid,
                    playingNow = false,
                    nowPlaying = null,
                    canPlay = false,
                    canPause = false,
                    holdsSession = false,
                ),
            )
        }
    }

    /**
     * Runs the finder, and offers what it finds to the card.
     *
     * The open of the app runs this, and the show-all control of the
     * Source card runs it again. The finder is the one view of every
     * installed media app, so each run forgets the drops: the apps a
     * swipe hid come back, and the card holds the whole answer of the
     * phone again. A swipe hides a row only until the next run of the
     * finder.
     */
    fun runFinder() {
        val found = finder?.installedMediaApps() ?: return
        mutableOffers.value = found
        mutableDiscarded.value = emptySet()
        refreshDisplay()
    }

    /** Hides one app from the offers, until the finder runs again. */
    fun discard(packageName: String) {
        mutableDiscarded.value = mutableDiscarded.value + packageName
        refreshDisplay()
    }

    /** Forgets every hidden app. The test hook uses it to start clean. */
    fun resetDiscards() {
        mutableDiscarded.value = emptySet()
        refreshDisplay()
    }

    /**
     * Starts the session reading.
     *
     * The registration fails with a security exception when the user has
     * not granted the notification listener access yet, so the reading
     * retires itself until the grant arrives.
     *
     * The reading also runs the finder, so the card opens with every
     * installed media app already dimmed among the sessions. The grant
     * path below stays quiet, because the probe needs no grant, and the
     * card must show the road to the toggle alone.
     */
    fun start(appContext: Context) {
        stop()
        context = appContext
        manager = appContext.getSystemService(MediaSessionManager::class.java)
        finder = InstalledMediaApps(appContext)
        val changed = MediaSessionManager.OnActiveSessionsChangedListener {
            read(appContext)
        }
        try {
            manager?.addOnActiveSessionsChangedListener(changed, ComponentName(appContext, PlayingAppsListener::class.java), handler)
        } catch (exception: SecurityException) {
            Log.i(TAG, "The media sessions are not readable, because the access is missing")
            mutableAccessMissing.value = true
            scheduleRetry(appContext, changed)
            return
        }
        listener = changed
        read(appContext)
        // The open of the app shows every installed media app on its
        // own, so the finder runs here. The show-all control of the
        // Source card runs it again, and each run forgets the drops.
        runFinder()
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
        mutableSessions.value = emptyList()
        mutableAccessMissing.value = false
        mutableOffers.value = emptyList()
        mutableDiscarded.value = emptySet()
        finder = null
        refreshDisplay()
        context = null
    }

    /**
     * The order of the Source card rows.
     *
     * The rows read in the alphabetical order of the app labels, so a row
     * keeps its place while it starts and stops, and the user's hand
     * learns the card. One label may stand for more than one session, so
     * the title of the session breaks the tie.
     */
    fun rowOrder(apps: List<SessionApp>): List<SessionApp> = apps.sortedWith(
        compareBy(
            { it.label.lowercase() },
            { it.nowPlaying?.lowercase() ?: "" },
        ),
    )

    /**
     * The rows the Source card carries: the sessions and the finder offers.
     *
     * The offers join the sessions in the alphabetical order, and an
     * offer a swipe hid stays away until the finder runs again, which
     * forgets every drop. An offer whose app holds a session
     * leaves, because the session row already speaks for that app, and
     * one row per app keeps the card honest. The package is the
     * identity the merge trusts first, because a service label can
     * differ from the app label. The names cover the harness, where a
     * test session plays under another package: the label means the
     * same app in both reads, and so does the session title that
     * carries the app's name on its lines.
     */
    fun visibleApps(
        sessions: List<SessionApp>,
        offers: List<SessionApp>,
        discarded: Set<String>,
    ): List<SessionApp> {
        val sessionPackages = sessions.mapTo(mutableSetOf()) { it.packageName }
        val sessionNames = sessions.flatMapTo(mutableSetOf()) { session ->
            buildSet {
                add(session.label.lowercase())
                val title = session.nowPlaying?.lowercase()
                if (title != null) {
                    title.split(Regex("\\W+")).filterTo(this) { it.isNotEmpty() }
                }
            }
        }
        val visible = sessions + offers.filter { offer ->
            offer.packageName !in discarded &&
                offer.packageName !in sessionPackages &&
                offer.label.lowercase() !in sessionNames
        }
        return rowOrder(visible)
    }

    /** Picks the app the capture should follow, by package name. */
    fun pick(packageName: String) {
        mutablePicked.value = packageName
    }

    /**
     * Forgets the picked app.
     *
     * The pick lives for as long as the process does, because a user
     * who stops a stream and taps the row again asks for a retry. The
     * BDD harness uses this between scenarios, so every scenario
     * starts from the state a fresh open shows.
     */
    fun forgetPick() {
        mutablePicked.value = null
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

    /**
     * Reads the sessions again on the next loop pass.
     *
     * The controller callbacks and the listener binding come here, and
     * so does the return to the app: a change that arrived while the
     * app stood behind another one can miss the change event, and the
     * card must not hold the stale rows until the next change.
     */
    fun reread() {
        val appContext = context ?: return
        handler.post { read(appContext) }
    }

    /**
     * The system bound the listener, so the grant has arrived.
     *
     * The binding is the earliest sign that the user allowed the access,
     * so the reading looks again instead of waiting for the retry. The
     * finder runs too, so the offers arrive without another tap from the
     * user, the way they arrive at the open of the app. The reading
     * stands down when the screen is closed, and the next start of the
     * screen reads anyway.
     */
    fun listenerConnected() {
        reread()
        handler.post { runFinder() }
    }

    /**
     * Reads the sessions of the device, and keeps one controller per app.
     *
     * The list holds every session that shows something: a session
     * either publishes its track, or its playback state says it plays
     * or rests. In normal use the sender holds no session, and the BDD
     * scenarios need their own session, which the instrumentation
     * creates in the app process.
     */
    private fun read(appContext: Context) {
        val sessionManager = manager ?: return
        val sessions = try {
            sessionManager.getActiveSessions(ComponentName(appContext, PlayingAppsListener::class.java))
        } catch (exception: SecurityException) {
            Log.i(TAG, "The media sessions are not readable, because the access is missing")
            mutableAccessMissing.value = true
            scheduleRetry(appContext, null)
            return
        }
        mutableAccessMissing.value = false
        val fresh = mutableMapOf<String, MediaController>()
        val currentPackages = mutableSetOf<String>()
        // Every active session carries a callback, whatever it shows
        // now: an app activates its session before it plays, and
        // publishes its track only when playback runs, so a session
        // that shows nothing today must still tell the card when it
        // starts to show something. No session change announces that
        // moment, and the reading must not wait for one.
        val list = sessions
            .distinctBy { it.packageName }
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
        mutableSessions.value = list
        refreshDisplay()
    }

    /**
     * One row of the list, or null when the session shows nothing yet.
     *
     * A row shows when the session publishes a track, or when its
     * playback state says it plays or rests. A session with neither is
     * a promise the card lists no row for, but the callback stays on
     * the session, so the row comes when the promise turns real.
     */
    private fun appOf(appContext: Context, controller: MediaController): SessionApp? {
        val packageName = controller.packageName
        val state = controller.playbackState
        if (controller.metadata == null && (state == null || state.state == PlaybackState.STATE_NONE)) {
            return null
        }
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
                // The description prints its empty fields as the word
                // "null" when it is read whole, so the line joins only
                // the fields that hold text.
                nowPlaying = controller.metadata?.description
                    ?.let { line ->
                        listOfNotNull(
                            line.title?.toString(),
                            line.subtitle?.toString(),
                        ).joinToString(" - ")
                    }
                    ?.ifEmpty { null },
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

    private const val TAG = "SpinDroid"
    private const val RETRY_MILLIS = 2_000L

    /** The first uid the test finder hands out: none of a real app. */
    private const val OFFER_UID_BASE = 90_000
}
