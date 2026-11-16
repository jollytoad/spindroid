package com.sendspin.sender.bdd.support

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import androidx.test.platform.app.InstrumentationRegistry

/**
 * The media session that the scenarios move.
 *
 * The sessions on the phone belong to other apps, so the scenarios cannot
 * start or pause them on demand. The test process therefore holds its own
 * session, which the app sees as one more app on the Source card. The
 * session answers play and pause, so the transport steps work on it too.
 *
 * The card names a session after its app, and the test process is not the
 * music app, so the step names the metadata title instead: the row shows
 * the title as its now-playing line.
 */
object TestSession {
    /**
     * The target context, because the instrumentation runs inside the app
     * process. A session that names any other package fails to start:
     * the system checks the package against the calling uid.
     */
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private var sessionMedia: MediaSession? = null

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /** The session must be built on a thread with a looper. */
    private fun onMain(block: () -> Unit) {
        instrumentation.runOnMainSync(block)
    }

    /** Starts a session, playing or paused. */
    fun start(title: String, playing: Boolean) {
        onMain {
            releaseNow()
            val fresh = MediaSession(context, "SpinDroid BDD session")
            fresh.setCallback(object : MediaSession.Callback() {
                override fun onPlay() = setState(true)
                override fun onPause() = setState(false)
            })
            fresh.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                    .build(),
            )
            fresh.setPlaybackState(stateOf(playing))
            fresh.isActive = true
            sessionMedia = fresh
        }
    }

    /** Drops the session, the way an app tears its session down. */
    fun release() {
        onMain { releaseNow() }
    }

    private fun releaseNow() {
        sessionMedia?.release()
        sessionMedia = null
    }

    /** Whether the session plays right now. */
    fun isPlaying(): Boolean {
        var playing = false
        onMain {
            playing = sessionMedia?.controller?.playbackState?.state == PlaybackState.STATE_PLAYING
        }
        return playing
    }

    /** Whether the app under test sees the session. */
    fun hasSession(): Boolean = sessionMedia != null

    private fun setState(playing: Boolean) {
        sessionMedia?.setPlaybackState(stateOf(playing))
    }

    private fun stateOf(playing: Boolean): PlaybackState = PlaybackState.Builder()
        .setActions(
            // Spotify publishes only the play-pause action, so the row
            // buttons must read that mask as the cover of both.
            PlaybackState.ACTION_PLAY_PAUSE
                or PlaybackState.ACTION_SEEK_TO,
        )
        .setState(
            if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
            0,
            if (playing) 1f else 0f,
        )
        .build()
}
