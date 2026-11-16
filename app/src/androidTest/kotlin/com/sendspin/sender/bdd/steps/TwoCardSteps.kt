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
import com.sendspin.sender.bdd.support.AdminApi
import com.sendspin.sender.bdd.support.ComposeRuleHolder
import com.sendspin.sender.bdd.support.Consent
import com.sendspin.sender.bdd.support.RealServer
import com.sendspin.sender.bdd.support.ServerPlayers
import com.sendspin.sender.bdd.support.TestSession
import com.sendspin.sender.capture.CaptureStatusHolder
import com.sendspin.sender.session.SessionStateHolder
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
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

    /** Serves the diagnosis of a wait that never ended. */
    private fun dumpScreen(tag: String) {
        val file = java.io.File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "spindroid-$tag.png",
        )
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(file)
    }

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


    @Given("the stream sends Tidal's audio to the first player")
    fun theStreamSendsToTheFirstPlayer() {
        TestSession.start(title("Tidal"), playing = true)
        waitForRow("Tidal")
        // The search finds the server a moment behind, so the option waits.
        try {
            composeRule.waitUntil(RealServer.SCREEN_TIMEOUT_MILLIS) {
                composeRule.onAllNodesWithText("Sign into Music Assistant")
                    .fetchSemanticsNodes().isNotEmpty()
            }
        } catch (exception: androidx.compose.ui.test.ComposeTimeoutException) {
            dumpScreen("sign-in")
            error("The sign in option never showed. A screenshot is in the app cache, sign-in.png.")
        }
        composeRule.onNodeWithText("Sign into Music Assistant").performClick()
        PairingSteps().theSignInFormIsFilledWithTheAccount()
        PairingSteps().buttonIsPressed("Sign in")
        theAppIsSignedIn()
        appIsPickedOnTheSourceCard("Tidal")
        firstPlayerIsPicked()
        // The bar does not scroll, so the control is in view for the press.
        composeRule.onNodeWithText(context.getString(R.string.action_connect)).performClick()
        Consent.waitPresented()
        Consent.choose("Start now")
        theStreamSendsToTheFirstPlayerThen()
    }

    /** Waits for the stream to the picked player, after the consent. */
    private fun theStreamSendsToTheFirstPlayerThen() {
        try {
            composeRule.waitUntil(RealServer.SCREEN_TIMEOUT_MILLIS) {
                composeRule.onAllNodesWithText(
                    context.getString(R.string.action_disconnect),
                ).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (exception: androidx.compose.ui.test.ComposeTimeoutException) {
            dumpScreen("stream")
            error("The control never read Disconnect. A screenshot is in /data/local/tmp.")
        }
        awaitUntil(AWAIT_STREAM_MILLIS) {
            CaptureStatusHolder.state.value.running &&
                (SessionStateHolder.state.value.streaming ||
                    SessionStateHolder.state.value.serverRequestedAudio)
        }
        check(ServerPlayers.awaitRouted(AWAIT_ROUTE_MILLIS)) {
            "The server never pointed a player at the phone."
        }
    }

    @When("{string} is chosen on the consent dialog")
    fun consentIsAnswered(option: String) {
        Consent.choose(option)
        composeRule.waitForIdle()
    }

    @Then("the media projection consent dialog is presented")
    fun theConsentDialogIsPresented() {
        Consent.waitPresented()
    }

    @Then("the phone streams to the first player")
    fun thePhoneStreamsToTheFirstPlayer() {
        val routed = ServerPlayers.awaitRouted(AWAIT_STREAM_MILLIS)
        if (!routed) {
            dumpScreen("route")
            error("The server never pointed a player at the phone.")
        }
        // A player that sits idle asks the phone for no audio, so the step
        // starts the transport the way a listener would press play on it.
        RealServer.find()
        val server = RealServer.found
            ?: error("The scenario needs a Music Assistant on the network.")
        try {
            AdminApi.playPlayer(
                serverUrl = server.url,
                token = AdminApi.signIn(server.url),
                playerId = ServerPlayers.firstId(),
            )
        } catch (exception: IllegalStateException) {
            val dump = AdminApi.dumpPlayers(server.url, AdminApi.signIn(server.url))
            dumpScreen("routes")
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
                .resolve("spindroid-players.json").writeText(dump)
            throw exception
        }
        val requested = awaitSilently(AWAIT_STREAM_MILLIS) {
            SessionStateHolder.state.value.streaming ||
                SessionStateHolder.state.value.serverRequestedAudio
        }
        val stateNow = SessionStateHolder.state.value
        check(requested) {
            "The server did not request the audio. routed=$routed paired=${stateNow.paired} " +
                "phase=${stateNow.phase} server=${stateNow.serverName}"
        }
    }

    /** Waits for a condition quietly, and reports what the last read said. */
    private fun awaitSilently(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(POLL_MILLIS)
        }
        return condition()
    }

    @Then("the notification offers Stop")
    fun theNotificationOffersStop() {
        // The app may read its own notifications without a permission. The
        // service publishes one while it streams, and its action offers
        // the stop.
        val manager = context.getSystemService(
            android.app.NotificationManager::class.java,
        )
        awaitUntil(AWAIT_STOP_MILLIS) {
            manager.activeNotifications.any { entry ->
                entry.notification.actions.any { action ->
                    action.title == context.getString(R.string.notification_stop)
                }
            }
        }
    }

    @Then("the stream stops")
    fun theStreamStops() {
        awaitUntil(AWAIT_STOP_MILLIS) {
            !SessionStateHolder.running.value && !SessionStateHolder.state.value.streaming
        }
    }

    @Then("the capture stops")
    fun theCaptureStops() {
        awaitUntil(AWAIT_STOP_MILLIS) { !CaptureStatusHolder.state.value.running }
    }

    @Then("the first player stays picked on the Destination card")
    fun theFirstPlayerStaysPicked() {
        val label = ServerPlayers.firstLabel()
        val node = composeRule.onNodeWithText(label, substring = true).fetchSemanticsNode()
        val radio = node.children.firstOrNull {
            runCatching { it.config[SemanticsProperties.Selected] }.getOrNull() == true
        } ?: error("The row of the first player has no radio to read.")
        check(radio.config[SemanticsProperties.Selected]) {
            "The first player is not picked."
        }
    }

    @When("the {word} card is viewed")
    fun cardIsViewed(title: String) {
        val label = if (title == "Source") {
            context.getString(R.string.title_source)
        } else {
            context.getString(R.string.title_destination)
        }
        composeRule.onNodeWithText(label).performScrollTo().assertIsDisplayed()
    }

    @Then("the Source radios are disabled")
    fun theSourceRadiosAreDisabled() {
        radioDisabledIn("Tidal")
    }

    @Then("the Destination radios are disabled")
    fun theDestinationRadiosAreDisabled() {
        radioDisabledIn(ServerPlayers.firstLabel())
    }

    /** Reads the radio of one row, and requires the disabled state. */
    private fun radioDisabledIn(name: String) {
        val node = composeRule.onNodeWithText(name, substring = true)
            .performScrollTo()
            .fetchSemanticsNode()
        val radio = node.children.firstOrNull {
            runCatching { it.config[SemanticsProperties.Selected] }.getOrNull() != null
        } ?: error("The row of $name has no radio to read.")
        check(runCatching { radio.config[SemanticsProperties.Disabled] }.getOrNull() != null) {
            "The radio of $name is not disabled."
        }
    }

    @And("the card says to disconnect before picking another source")
    fun theCardSaysToDisconnectForSources() {
        composeRule.onNodeWithText(
            context.getString(R.string.source_locked),
        ).performScrollTo().assertIsDisplayed()
    }

    @And("the card says to disconnect before picking another player")
    fun theCardSaysToDisconnectForPlayers() {
        composeRule.onNodeWithText(
            context.getString(R.string.destination_locked),
        ).performScrollTo().assertIsDisplayed()
    }

    @And("a status line under the bar says the source vanished")
    fun aStatusLineSaysTheSourceVanished() {
        composeRule.onNodeWithText(
            context.getString(R.string.source_vanished),
        ).assertIsDisplayed()
    }

    @And("the stream keeps sending silence to the first player")
    fun theStreamKeepsSendingSilence() {
        awaitUntil(AWAIT_STREAM_MILLIS) { CaptureStatusHolder.state.value.running }
        check(SessionStateHolder.running.value) { "The service stopped." }
    }

    @And("the control still reads {string}")
    fun theControlStillReads(text: String) {
        theControlReads(text)
    }

    private companion object {
        /** How long the session has to answer the transport commands. */
        const val AWAIT_PLAY_MILLIS = 10_000L

        /** How long the stream may take to reach the picked player. */
        const val AWAIT_STREAM_MILLIS = 30_000L

        /** How long the server may take to route the player. */
        const val AWAIT_ROUTE_MILLIS = 60_000L

        /** How long the stop may take. */
        const val AWAIT_STOP_MILLIS = 15_000L

        /** How long a wait sleeps between two checks. */
        const val POLL_MILLIS = 250L
    }
}
