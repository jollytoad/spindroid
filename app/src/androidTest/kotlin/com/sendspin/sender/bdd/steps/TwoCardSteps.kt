package com.sendspin.sender.bdd.steps

import android.content.pm.ActivityInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import kotlin.math.abs
import com.sendspin.sender.R
import com.sendspin.sender.bdd.support.ComposeRuleHolder
import com.sendspin.sender.bdd.support.RealServer
import com.sendspin.sender.bdd.support.ServerPlayers
import com.sendspin.sender.bdd.support.TestSession
import io.cucumber.java.After
import io.cucumber.java.en.And
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When

/**
 * The steps of the two-card screen.
 *
 * The steps drive the two cards with the app's own strings, so a rename
 * of the screen changes the steps with it. The steps read the session
 * list the way the user sees it, and the test session is one row among
 * them.
 *
 * The first player of the server is the stand-in for a named player,
 * because the player names belong to the server, not to the scenario.
 */
class TwoCardSteps {
    private val composeRule get() = ComposeRuleHolder.current.composeRule
    private val context
        get() = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .targetContext

    @After
    fun turnPortraitAgain() {
        composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    @Given("no media app holds a session")
    fun noMediaAppHoldsASession() {
        TestSession.release()
        composeRule.waitForIdle()
    }

    @Given("Tidal holds a playing session")
    fun tidalHoldsAPlayingSession() {
        TestSession.start(title("Tidal"), playing = true)
        waitForRow("Tidal")
    }

    @Given("Tidal holds a paused session")
    fun tidalHoldsAPausedSession() {
        TestSession.start(title("Tidal"), playing = false)
        waitForRow("Tidal")
    }

    @Given("the app is signed into Music Assistant")
    fun theAppIsSignedIn() {
        // The sign in runs on the network after the press, so the step
        // waits for the sign out button: the main screen shows it only
        // with an account.
        composeRule.waitUntil(RealServer.SCREEN_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText(
                context.getString(R.string.action_sign_out),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
    }

    @Given("the app is open")
    fun theAppIsOpen() {
        composeRule.waitForIdle()
    }

    @When("Spotify starts a media session")
    fun spotifyStartsAMediaSession() {
        TestSession.start(title("Spotify"), playing = true)
        waitForRow("Spotify")
    }

    @When("Spotify drops its media session")
    fun spotifyDropsItsMediaSession() {
        TestSession.release()
    }

    @When("Tidal drops its media session")
    fun tidalDropsItsMediaSession() {
        TestSession.release()
    }

    @When("{string} is picked on the Source card")
    fun appIsPickedOnTheSourceCard(app: String) {
        composeRule.waitForIdle()
        composeRule.onNode(row(app)).performScrollTo().performClick()
        composeRule.waitForIdle()
    }

    @When("the first player is picked on the Destination card")
    fun firstPlayerIsPicked() {
        val label = ServerPlayers.firstLabel()
        composeRule.onNode(row(label)).performScrollTo().performClick()
        composeRule.waitForIdle()
    }

    @When("the phone is turned to landscape")
    fun thePhoneIsTurnedToLandscape() {
        composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        waitForCards()
    }

    @When("{word} is pressed on the {word} row")
    fun transportIsPressedOnTheRow(button: String, app: String) {
        composeRule.waitForIdle()
        composeRule.onNodeWithText(button).performClick()
        composeRule.waitForIdle()
    }

    @Then("the Connect control is disabled")
    fun theConnectControlIsDisabled() {
        control().assertIsNotEnabled()
    }

    @Then("the Connect control is enabled")
    fun theConnectControlIsEnabled() {
        control().assertIsEnabled()
    }

    @Then("the control reads {string}")
    fun theControlReads(text: String) {
        composeRule.onNodeWithText(text).assertIsDisplayed()
    }

    @Then("the Destination card presents connection options:")
    fun theDestinationCardPresentsConnectionOptions(options: io.cucumber.datatable.DataTable) {
        options.asLists().forEach { line ->
            composeRule.onNodeWithText(line[0]).assertIsDisplayed()
        }
    }

    @Then("the Source card says that no app holds a media session")
    fun theSourceCardSaysThatNoAppHoldsASession() {
        composeRule.onNodeWithText(
            context.getString(R.string.source_empty),
        ).assertIsDisplayed()
    }

    @Then("the Destination card asks for a player pick")
    fun theDestinationCardAsksForAPick() {
        composeRule.onNodeWithText(
            context.getString(R.string.players_sign_in_first),
        ).assertIsDisplayed()
    }

    @Then("the Source card lists {word}")
    fun theSourceCardLists(app: String) {
        composeRule.onNode(row(app)).assertIsDisplayed()
    }

    @Then("the Source card no longer lists {word}")
    fun theSourceCardNoLongerLists(app: String) {
        composeRule.waitUntil(RealServer.SCREEN_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText(app, substring = true).fetchSemanticsNodes().isEmpty()
        }
    }

    @Then("Tidal plays again")
    fun tidalPlaysAgain() {
        awaitUntil(AWAIT_PLAY_MILLIS) { TestSession.isPlaying() }
    }

    @Then("the Source card and the Destination card sit side by side")
    fun theCardsSitSideBySide() {
        val source = composeRule.onNodeWithText(
            context.getString(R.string.title_source),
        ).fetchSemanticsNode().positionInRoot
        val destination = composeRule.onNodeWithText(
            context.getString(R.string.title_destination),
        ).fetchSemanticsNode().positionInRoot
        check(source.x < destination.x) {
            "The Destination card ended at x=${destination.x}, left of the Source card."
        }
        check(abs(source.y - destination.y) < 200f) {
            "The cards are stacked: Source at y=${source.y}, Destination at y=${destination.y}."
        }
    }

    @Then("{string} stays picked on the Source card")
    fun appStaysPicked(app: String) {
        // The radio of a row is its own merge boundary, so the row node
        // carries no toggle state of its own. The step reads the children.
        val node = composeRule.onNode(row(app)).fetchSemanticsNode()
        val radio = node.children.firstOrNull {
            runCatching { it.config[SemanticsProperties.Selected] }.getOrNull() == true
        } ?: error("The row of $app has no radio to read.")
        check(radio.config[SemanticsProperties.Selected]) {
            "The row of $app is not picked."
        }
    }

    /** The node of one Source card row, named after the session title. */
    private fun row(name: String) = hasText(name, substring = true) and hasClickAction()

    /** The title that the test session carries, as the row shows it. */
    private fun title(app: String) = "$app — the BDD session"

    /** The stream control of the bar. */
    private fun control() = composeRule.onNodeWithText(
        context.getString(R.string.action_connect),
    )

    /** Waits until a row with the name is on screen. */
    private fun waitForRow(name: String) {
        composeRule.waitUntil(RealServer.SCREEN_TIMEOUT_MILLIS) {
            composeRule.onAllNodes(row(name)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(row(name)).assertIsDisplayed()
    }

    /** Waits until both card titles are on screen. */
    private fun waitForCards() {
        composeRule.waitUntil(RealServer.SCREEN_TIMEOUT_MILLIS) {
            val source = composeRule.onAllNodesWithText(
                context.getString(R.string.title_source),
            ).fetchSemanticsNodes().isNotEmpty()
            val destination = composeRule.onAllNodesWithText(
                context.getString(R.string.title_destination),
            ).fetchSemanticsNodes().isNotEmpty()
            source && destination
        }
    }

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
        /** How long the session has to answer the transport commands. */
        const val AWAIT_PLAY_MILLIS = 10_000L

        /** How long a wait sleeps between two checks. */
        const val POLL_MILLIS = 250L
    }
}
