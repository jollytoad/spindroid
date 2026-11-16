package com.sendspin.sender

import com.sendspin.sender.capture.SessionApps.SessionApp
import com.sendspin.sender.capture.SessionApps.followTarget
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
