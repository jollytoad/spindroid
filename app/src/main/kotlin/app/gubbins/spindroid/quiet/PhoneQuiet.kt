package app.gubbins.spindroid.quiet

import android.content.Context
import android.media.AudioManager
import android.util.Log

/**
 * Keeps the phone quiet while it streams.
 *
 * The destination plays the sound the phone sends, so the phone's own
 * speaker doubles it. The capture reads the sound before the phone's
 * volume applies to it, so the phone may go to zero without taking the
 * stream with it — the BDD scenario "What the capture carries when the
 * volume falls" guards that fact.
 *
 * The phone comes back when the stream ends. Three rules hold:
 * a phone the user muted on purpose is never touched, the hand of the
 * user always wins over the restore, and only the change this object
 * made is undone.
 */
object PhoneQuiet {
    private const val TAG = "SpinDroid"

    /** The volume the phone read before this object muted it. */
    private var remembered: Int? = null

    /**
     * Mutes the phone, and remembers what to bring back.
     *
     * A phone that already reads zero belongs to the user, so it stays
     * as it is: the restore must never unmute a phone on purpose. The
     * call is safe to repeat while a stream runs, because the phone
     * then reads zero and the call goes home without a change.
     */
    fun begin(context: Context) {
        val audio = audioOf(context) ?: return
        val now = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (now == 0) return
        try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        } catch (exception: SecurityException) {
            // The phone sits under a rule that forbids the change, such
            // as the do-not-disturb mode, so the phone stays as it is.
            Log.i(TAG, "The phone did not go quiet: ${exception.message}")
            return
        }
        remembered = now
    }

    /**
     * Brings the phone back, when this object muted it.
     *
     * The user may have moved the volume while the phone streamed, from
     * another screen: the restore steps aside then, because the hand of
     * the user already said how loud the phone should read.
     */
    fun end(context: Context) {
        val audio = audioOf(context) ?: return
        val level = remembered ?: return
        remembered = null
        if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) != 0) return
        try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0)
        } catch (exception: SecurityException) {
            Log.i(TAG, "The phone did not come back: ${exception.message}")
        }
    }

    /** The audio manager of the phone, or null on a broken device. */
    private fun audioOf(context: Context): AudioManager? =
        context.getSystemService(AudioManager::class.java)
}
