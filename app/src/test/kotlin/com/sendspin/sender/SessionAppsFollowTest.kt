package com.sendspin.sender

import com.sendspin.sender.capture.SessionApps
import com.sendspin.sender.capture.SessionApps.SessionApp
import com.sendspin.sender.capture.SessionApps.followTarget
import com.sendspin.sender.capture.SessionApps.rowOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The rule the pick follows with the audio focus.
 *
 * The phone plays one media app at a time, and the well-behaved apps
 * agree over the audio focus about which one it is. The pick follows the
 * focus, so the stream carries the sound that actually plays.
 */
class SessionAppsFollowTest {

    @Test
    fun `the pick moves to the one app that plays`() {
        assertEquals(
            "spotify",
            followTarget(
                picked = "tidal",
                apps = listOf(app("Tidal", playing = false), app("Spotify", playing = true)),
            ),
        )
    }

    @Test
    fun `the pick stays while the picked app plays`() {
        // Two apps play over the focus at once, or the read caught a
        // switch: both are not a focus change away from the pick.
        assertNull(
            followTarget(
                picked = "tidal",
                apps = listOf(app("Tidal", playing = true), app("Spotify", playing = true)),
            ),
        )
    }

    @Test
    fun `the pick stays while nothing plays`() {
        assertNull(
            followTarget(
                picked = "tidal",
                apps = listOf(app("Tidal", playing = false), app("Spotify", playing = false)),
            ),
        )
    }

    @Test
    fun `the pick stays while two other apps fight over the focus`() {
        // Without the count of one, the pick would bounce between the two
        // fighting apps, and each bounce switches the capture over.
        assertNull(
            followTarget(
                picked = "tidal",
                apps = listOf(
                    app("Tidal", playing = false),
                    app("Spotify", playing = true),
                    app("Radio", playing = true),
                ),
            ),
        )
    }

    @Test
    fun `a vanished pick stays away from the follower`() {
        // The vanished pick has its own status line, so the follower does
        // not turn a vanish into a silent move to another app.
        assertNull(
            followTarget(
                picked = "tidal",
                apps = listOf(app("Spotify", playing = true)),
            ),
        )
    }

    @Test
    fun `there is no follow before a pick`() {
        assertNull(followTarget(picked = null, apps = listOf(app("Spotify", playing = true))))
    }

    @Test
    fun `the rows read in the alphabetical order of the apps`() {
        val rows = SessionApps.rowOrder(
            listOf(
                app("Mellow", playing = true),
                app("Anchor", playing = false),
            ),
        )
        check(rows.map { it.label } == listOf("Anchor", "Mellow")) {
            "The playing app came first, but the rows must read ${rows.map { it.label }}."
        }
    }

    @Test
    fun `a start and a stop do not move a row`() {
        // The order reads the labels alone, so the play state, which
        // flips from second to second, does not reorder the card.
        val label = "Spotify"
        val rowsPlaying = SessionApps.rowOrder(
            listOf(app(label, playing = true), app("Tidal", playing = false)),
        )
        val rowsPaused = SessionApps.rowOrder(
            listOf(app(label, playing = false), app("Tidal", playing = false)),
        )
        check(
            rowsPlaying.map { it.label } == rowsPaused.map { it.label },
        ) {
            "The order changed when the play state changed."
        }
    }

    @Test
    fun `the title breaks the tie of one label`() {
        // The BDD harness holds several sessions of one app process, so
        // the rows of one label read by their titles.
        val one = app("SpinDroid", playing = false).copy(nowPlaying = "Tidal — the BDD session")
        val two = app("SpinDroid", playing = false).copy(nowPlaying = "Spotify — the BDD session")
        val rows = SessionApps.rowOrder(listOf(one, two))
        check(rows[0].nowPlaying!!.startsWith("Spotify")) {
            "The tie did not read by the title."
        }
    }

    @Test
    fun `the finder offers read among the sessions`() {
        val rows = SessionApps.visibleApps(
            sessions = listOf(app("Tidal", playing = true)),
            offers = listOf(offer("Radio"), offer("Mellow")),
            discarded = emptySet(),
        )
        check(rows.map { it.label } == listOf("Mellow", "Radio", "Tidal")) {
            "The rows must read alphabetically, which gives ${rows.map { it.label }}."
        }
    }

    @Test
    fun `a finder offer leaves when its app holds a session`() {
        val rows = SessionApps.visibleApps(
            sessions = listOf(app("Tidal", playing = true)),
            offers = listOf(offer("Tidal")),
            discarded = emptySet(),
        )
        check(rows.size == 1) {
            "The app must not carry two rows, but the rows read ${rows.map { it.label }}."
        }
    }

    @Test
    fun `a discarded finder offer stays away`() {
        val rows = SessionApps.visibleApps(
            sessions = listOf(app("Tidal", playing = true)),
            offers = listOf(offer("Radio"), offer("Mellow")),
            discarded = setOf("radio"),
        )
        check(rows.map { it.label } == listOf("Mellow", "Tidal")) {
            "The discarded app must not come back, which gives ${rows.map { it.label }}."
        }
    }

    @Test
    fun `a finder offer leaves when a session row names it`() {
        // The session row shows the title as its line, so the app the
        // line names must not carry a second, not-running row.
        val row = app("SpinDroid", playing = false).copy(nowPlaying = "Tidal — the BDD session")
        val rows = SessionApps.visibleApps(
            sessions = listOf(row),
            offers = listOf(offer("Tidal")),
            discarded = emptySet(),
        )
        check(rows.size == 1) {
            "The titled session must speak for the app, which gives ${rows.map { it.label }}."
        }
    }

    /** One app that answered the finder, and holds no session. */
    private fun offer(name: String): SessionApp = app(name, playing = false)
        .copy(nowPlaying = null, holdsSession = false)

    private fun app(name: String, playing: Boolean): SessionApp = SessionApp(
        packageName = name.lowercase(),
        label = name,
        uid = 10000,
        playingNow = playing,
        nowPlaying = name,
        canPlay = true,
        canPause = true,
    )
}
