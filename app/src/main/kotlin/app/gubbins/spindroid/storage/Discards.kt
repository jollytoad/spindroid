package app.gubbins.spindroid.storage

import android.content.Context
import androidx.core.content.edit

/**
 * The rows a swipe hid, kept between runs of the app.
 *
 * A swipe is a choice the user made about the card, and a restart of the
 * app or a turn of the screen is no reason to undo it: the rows stay
 * hidden until the user presses the control that shows them all. The
 * ids carry no secret, so they sit in plain preferences.
 */
class Discards(context: Context) {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /**
     * The media apps a swipe hid, by package name.
     *
     * The package names come from the finder, so they name the same app
     * after a restart as they named before it.
     */
    var apps: Set<String>
        get() = preferences.getStringSet(KEY_APPS, null) ?: emptySet()
        set(value) = preferences.edit { putStringSet(KEY_APPS, value) }

    /**
     * The players a swipe hid, by player id.
     *
     * The server owns the ids, so they name the same player after a
     * restart as they named before it.
     */
    var players: Set<String>
        get() = preferences.getStringSet(KEY_PLAYERS, null) ?: emptySet()
        set(value) = preferences.edit { putStringSet(KEY_PLAYERS, value) }

    private companion object {
        const val FILE_NAME = "sendspin_discards"
        const val KEY_APPS = "apps"
        const val KEY_PLAYERS = "players"
    }
}
