package app.gubbins.spindroid.bdd.support

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
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
            fresh.setMetadata(metadataOf(title))
            fresh.setPlaybackState(stateOf(playing))
            fresh.isActive = true
            sessionMedia = fresh
        }
    }

    /**
     * Starts a session whose playback state offers no transport action,
     * so the row that reads it carries no button at all.
     */
    fun startUnresponsive(title: String) {
        onMain {
            releaseNow()
            val fresh = MediaSession(context, "SpinDroid BDD session")
            fresh.setMetadata(metadataOf(title))
            fresh.setPlaybackState(stateOf(playing = false, actions = 0L))
            fresh.isActive = true
            sessionMedia = fresh
        }
    }

    /**
     * Starts a session the way the real apps do: the session activates
     * with a playback state and no track, and the track arrives only
     * when the scenario publishes it.
     */
    fun startQuiet(playing: Boolean) {
        onMain {
            releaseNow()
            val fresh = MediaSession(context, "SpinDroid BDD session")
            fresh.setCallback(object : MediaSession.Callback() {
                override fun onPlay() = setState(true)
                override fun onPause() = setState(false)
            })
            fresh.setPlaybackState(stateOf(playing))
            fresh.isActive = true
            sessionMedia = fresh
        }
    }

    /** Publishes the track of the quiet session, the way playback does. */
    fun publishTitle(track: String) {
        onMain { sessionMedia?.setMetadata(metadataOf(track)) }
    }

    /** Drops the session, the way an app tears its session down. */
    fun release() {
        onMain { releaseNow() }
    }

    /**
     * The tone that makes real sound on the phone.
     *
     * The session alone makes no sound, so the checks that read what
     * the capture carries need a voice. The tone belongs to the test
     * process, which the app sees as the picked session, so the
     * capture follows it the way it follows a real music app.
     */
    private var toneTrack: AudioTrack? = null

    /**
     * Plays a steady tone, or stops it.
     *
     * The tone loops until [tone] turns it off. It plays through the
     * media usage, so the capture of the system audio takes it in.
     */
    fun tone(on: Boolean) {
        onMain {
            if (!on) {
                toneTrack?.run {
                    stop()
                    release()
                }
                toneTrack = null
                return@onMain
            }
            if (toneTrack != null) return@onMain
            val rate = TONE_RATE
            // Two seconds of a plain sine, played in a loop, so the
            // sound stays steady while the checks read the level.
            val samples = ShortArray(rate * TONE_SECONDS) { index ->
                (Math.sin(2.0 * Math.PI * TONE_HERTZ * index / rate) * Short.MAX_VALUE * 0.8)
                    .toInt().toShort()
            }
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 2)
                .build()
            track.write(samples, 0, samples.size)
            track.setLoopPoints(0, samples.size, -1)
            track.play()
            toneTrack = track
        }
    }

    private fun releaseNow() {
        sessionMedia?.release()
        sessionMedia = null
    }

    /** The pitch of the tone, in hertz. */
    private const val TONE_HERTZ = 440.0

    /** The rate of the tone, in samples per second. */
    private const val TONE_RATE = 48_000

    /** The length of the tone buffer, in seconds. */
    private const val TONE_SECONDS = 2

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

    /** The title that the session's metadata carries. */
    private fun metadataOf(title: String): MediaMetadata = MediaMetadata.Builder()
        .putString(MediaMetadata.METADATA_KEY_TITLE, title)
        .build()

    /**
     * The playback state of the session, with the actions it honours.
     *
     * The default mask is the one Spotify publishes: some apps offer
     * only the play-pause action, and the row buttons must read that
     * mask as the cover of both. A session that honours nothing
     * carries no actions, and its row carries no button.
     */
    private fun stateOf(
        playing: Boolean,
        actions: Long = PlaybackState.ACTION_PLAY_PAUSE
            or PlaybackState.ACTION_SEEK_TO,
    ): PlaybackState = PlaybackState.Builder()
        .setActions(actions)
        .setState(
            if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
            0,
            if (playing) 1f else 0f,
        )
        .build()
}
