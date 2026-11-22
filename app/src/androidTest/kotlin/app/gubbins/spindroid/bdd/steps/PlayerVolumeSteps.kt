package app.gubbins.spindroid.bdd.steps

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.platform.app.InstrumentationRegistry
import app.gubbins.spindroid.R
import app.gubbins.spindroid.bdd.support.AdminApi
import app.gubbins.spindroid.bdd.support.ComposeRuleHolder
import app.gubbins.spindroid.bdd.support.RealServer
import app.gubbins.spindroid.bdd.support.ServerPlayers
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import io.cucumber.java.After

/**
 * The steps that drive the loudness of the players.
 *
 * The truth of a player's loudness lives on the server, so the steps
 * read it through the administrator API rather than through the app:
 * a step that read the app's own state would only echo back what the
 * app said it did.
 *
 * The players are real speakers in a real home, so the steps stay
 * gentle: no step drives a volume high, and every volume a scenario
 * moves goes back to what it read before the scenario touched it.
 */
class PlayerVolumeSteps {
    private val composeRule get() = ComposeRuleHolder.current.composeRule
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** The loudness each touched player read before the scenario moved it. */
    private val beforeScenario = mutableMapOf<String, Int>()

    @After
    fun putTheVolumesBack() {
        // The speakers belong to a home, not to the suite, so every
        // volume a scenario moved returns to where it read before.
        for ((playerId, volume) in beforeScenario) {
            try {
                AdminApi.setVolume(theServer().url, token(), playerId, volume)
            } catch (ignored: Exception) {
                // A scenario that never found the server has nothing to
                // put back.
            }
        }
        beforeScenario.clear()
    }

    @io.cucumber.java.en.Given("the {word} player's volume is set to {int}")
    fun thePlayersVolumeIsSetTo(player: String, volume: Int) {
        val id = playerVolumeId(player)
        rememberWhatItRead(id)
        adminSetVolume(id, volume)
        awaitUntil(AWAIT_VOLUME_MILLIS) { adminVolumeOf(id) == volume }
        // The app reads the list again on its own clock, and the volume
        // keys step below moves the loudness from what the app read, so
        // the settle waits for that read too.
        Thread.sleep(SETTLE_MILLIS)
    }

    @io.cucumber.java.en.Then("the {word} player's volume reads {int}")
    fun thePlayersVolumeReads(player: String, volume: Int) {
        val id = playerVolumeId(player)
        awaitUntil(AWAIT_VOLUME_MILLIS) { adminVolumeOf(id) == volume }
    }

    @io.cucumber.java.en.Then("the {word} player's volume reads between {int} and {int}")
    fun thePlayersVolumeReadsBetween(player: String, low: Int, high: Int) {
        val id = playerVolumeId(player)
        awaitUntil(AWAIT_VOLUME_MILLIS) {
            val now = adminVolumeOf(id) ?: -1
            now in low..high
        }
    }

    @io.cucumber.java.en.When("the volume key is pressed {int} times")
    fun theVolumeKeyIsPressed(times: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        composeRule.waitForIdle()
        repeat(times) {
            // The key goes to the window that holds the focus, which the
            // rule keeps on the app, the way a hand on the phone would.
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_VOLUME_UP)
            composeRule.waitForIdle()
        }
    }

    @io.cucumber.java.en.When("the slider of the {word} player is nudged a little")
    fun theSliderIsNudgedALittle(player: String) {
        val id = playerVolumeId(player)
        rememberWhatItRead(id)
        val label = playerLabel(player)
        composeRule.waitForIdle()
        val slider = composeRule.onNodeWithContentDescription(
            context.getString(R.string.players_volume, label),
        )
        slider.performScrollTo()
        // The slider takes a drag that starts on its thumb, wherever
        // the value left it, so the gesture starts there and moves a
        // short way along the track, the way a careful hand would.
        val before = slider.fetchSemanticsNode()
            .config[SemanticsProperties.ProgressBarRangeInfo].current
        slider.performTouchInput {
            swipe(
                start = center.copy(x = width * before),
                end = center.copy(x = width * (before + NUDGE)),
                durationMillis = DRAG_MILLIS.toLong(),
            )
        }
        composeRule.waitForIdle()
    }

    /**
     * Keeps the loudness a player read before the scenario moved it.
     *
     * The first touch wins, so a scenario that sets a baseline and then
     * moves the slider still puts back the loudness the home had.
     */
    private fun rememberWhatItRead(playerId: String) {
        if (playerId in beforeScenario) return
        beforeScenario[playerId] = adminVolumeOf(playerId) ?: 0
    }

    /** The label of the player the step names, first or second. */
    private fun playerLabel(player: String): String = when (player) {
        "first" -> ServerPlayers.firstLabel()
        "second" -> ServerPlayers.secondLabel()
        else -> error("The step names the first or the second player, not $player.")
    }

    /** The identifier of the player the step names, first or second. */
    private fun playerVolumeId(player: String): String = when (player) {
        "first" -> ServerPlayers.firstId()
        "second" -> ServerPlayers.secondId()
        else -> error("The step names the first or the second player, not $player.")
    }

    /** The loudness of one player, straight from the server. */
    private fun adminVolumeOf(playerId: String): Int? =
        AdminApi.volumeOf(theServer().url, token(), playerId)

    /** Sets the loudness of one player, straight on the server. */
    private fun adminSetVolume(playerId: String, volume: Int) {
        AdminApi.setVolume(theServer().url, token(), playerId, volume)
    }

    /** The server that the app expects to show. */
    private fun theServer(): app.gubbins.spindroid.mass.DiscoveredServer {
        // A scenario that never searched for the server still needs one
        // here, so the search runs only when no earlier step found it.
        if (RealServer.found == null) RealServer.find()
        return RealServer.found ?: error("No Music Assistant answered on the network.")
    }

    /** The administrator token of the server. */
    private fun token(): String = AdminApi.signIn(theServer().url)

    /** Waits until the condition holds, and fails when it never does. */
    private fun awaitUntil(timeoutMillis: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(POLL_MILLIS)
        }
        check(condition()) { "The condition did not hold within ${timeoutMillis / 1000} seconds." }
    }

    private companion object {
        /** How long the server may take to read the loudness back. */
        const val AWAIT_VOLUME_MILLIS = 15_000L

        /** How long the app takes to read the list again on its own. */
        const val SETTLE_MILLIS = 3_000L

        /** How far a nudge moves along the track, as a share of it. */
        const val NUDGE = 0.1f

        /** How long a drag on a slider takes, like a hand would. */
        const val DRAG_MILLIS = 1_200

        /** How long a wait sleeps between two checks. */
        const val POLL_MILLIS = 500L
    }
}
