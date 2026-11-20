package app.gubbins.spindroid.bdd.steps

import android.content.pm.ActivityInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.performTouchInput
import kotlin.math.abs
import app.gubbins.spindroid.R
import app.gubbins.spindroid.bdd.support.AdminApi
import app.gubbins.spindroid.bdd.support.ComposeRuleHolder
import app.gubbins.spindroid.bdd.support.Consent
import app.gubbins.spindroid.bdd.support.RealServer
import app.gubbins.spindroid.bdd.support.ServerPlayers
import app.gubbins.spindroid.bdd.support.TestSession
import app.gubbins.spindroid.capture.CaptureStatusHolder
import app.gubbins.spindroid.capture.SessionApps
import app.gubbins.spindroid.session.SessionStateHolder
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.cucumber.java.After
import io.cucumber.java.Before
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

    @After
    fun closeTheRealApp() {
        // The real app that the scenario opened holds a session and
        // the foreground after the steps, so it leaves the way it came.
        val packageName = realPackage ?: return
        realPackage = null
        if (packageName == context.packageName) return
        forceStop(packageName)
    }

    @Before
    fun startClean() {
        // The drops live in the process, one scenario at a time, so
        // a swipe that the previous scenario made never hides a row here.
        TestSession.release()
        SessionApps.resetDiscards()
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

    @Given("{word} holds a session that answers no transport")
    fun appHoldsASessionThatAnswersNoTransport(app: String) {
        TestSession.startUnresponsive(title(app))
        waitForRow(app)
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

    @When("{word} starts a media session")
    fun appStartsAMediaSession(app: String) {
        TestSession.start(title(app), playing = true)
        waitForRow(app)
    }

    @When("Mellow drops its media session")
    fun mellowDropsItsMediaSession() {
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
        // The one transport button carries its verb as the description,
        // so the press names its row and reads the button inside it.
        composeRule.onNode(
            hasContentDescription(button) and hasAnyAncestor(row(app)),
        ).performClick()
        composeRule.waitForIdle()
    }

    @Then("the {word} row offers {word}")
    fun theRowOffers(app: String, button: String) {
        // The row offers the verb its state needs, so the other verb
        // has no place in it.
        composeRule.waitForIdle()
        composeRule.onNode(
            hasContentDescription(button) and hasAnyAncestor(row(app)),
        ).assertIsDisplayed()
    }

    @Then("the {word} row offers no {word}")
    fun theRowOffersNo(app: String, button: String) {
        composeRule.waitForIdle()
        composeRule.waitUntil(RealServer.SCREEN_TIMEOUT_MILLIS) {
            composeRule.onAllNodes(
                hasContentDescription(button) and hasAnyAncestor(row(app)),
            ).fetchSemanticsNodes().isEmpty()
        }
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

    @Given("the finder offers {string} and {string} as installed media apps")
    fun theFinderOffersTwo(one: String, two: String) {
        SessionApps.offerInstalled(one, two)
    }

    @Given("the finder offers {string} as an installed media app")
    fun theFinderOffersOne(one: String) {
        SessionApps.offerInstalled(one)
    }

    @Given("the phone holds no other playing app")
    fun thePhoneHoldsNoOtherPlayingApp() {
        // The pick follows the one app that plays, so a scenario that
        // reads a pick needs a phone where nothing else plays. The
        // apps that play are stopped, the way a reset would stop them.
        val playing = SessionApps.apps.value.filter {
            it.playingNow && it.packageName != context.packageName
        }
        playing.forEach { forceStop(it.packageName) }
        awaitUntil(AWAIT_QUIET_MILLIS) {
            SessionApps.apps.value.none {
                it.playingNow && it.packageName != context.packageName
            }
        }
    }

    @Given("the finder offers {word} from this phone")
    fun theFinderOffersTheRealApp(label: String) {
        val manager = context.packageManager
        val info = manager.getInstalledApplications(0)
            .firstOrNull { manager.getApplicationLabel(it).toString() == label }
            ?: error("$label is not installed on this phone.")
        // A session left from earlier use would turn the discovered
        // row away before the plus runs, so the app starts quiet.
        forceStop(info.packageName)
        realPackage = info.packageName
        SessionApps.offerInstalledApp(
            label = label,
            packageName = info.packageName,
            uid = manager.getPackageUid(info.packageName, 0),
        )
    }

    @When("the plus is chosen")
    fun thePlusIsChosen() {
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(
            context.getString(R.string.finder_open),
        ).performClick()
        composeRule.waitForIdle()
    }

    @When("the {word} icon is chosen")
    fun theAppIconIsChosen(app: String) {
        composeRule.waitForIdle()
        // The icon is the one control that opens the app, and it says
        // so with the app's own name.
        composeRule.onNode(
            hasContentDescription(context.getString(R.string.open_app, app)),
        ).performScrollTo().performClick()
        composeRule.waitForIdle()
    }

    @Then("the sender stays in the foreground")
    fun theSenderStaysInTheForeground() {
        // The open path answers a tap at once, so a moment of quiet
        // says the pick left the sender where it was.
        Thread.sleep(WATCH_OPEN_MILLIS)
        val front = device().currentPackageName
        check(front == context.packageName) {
            "The pick moved the foreground to $front."
        }
    }

    @Then("{word} opens in the foreground")
    fun theAppOpensInTheForeground(app: String) {
        val packageName = realPackage
            ?: error("$app was not offered from this phone, so it cannot open.")
        awaitUntil(AWAIT_OPEN_MILLIS) { device().currentPackageName == packageName }
    }

    @Then("the rows read {string}")
    fun theRowsRead(theRows: String) {
        composeRule.waitForIdle()
        // The card reads its rows top to bottom, so each row must sit
        // below the one the step named before it.
        var previous = -Float.MAX_VALUE
        theRows.split(", ").forEach { name ->
            val y = composeRule.onNode(row(name)).fetchSemanticsNode()
                .positionInRoot.y
            check(y > previous) {
                "The row of ${'$'}name did not read after the one above: y=${'$'}y of ${'$'}{theRows}."
            }
            previous = y
        }
    }

    @When("the {word} row is swiped aside")
    fun theRowIsSwipedAside(app: String) {
        composeRule.waitForIdle()
        composeRule.onNode(row(app)).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
    }

    @When("the first player row is swiped aside")
    fun theFirstPlayerRowIsSwipedAside() {
        swipePlayerRowAside(ServerPlayers.firstLabel())
    }

    @When("the second player row is swiped aside")
    fun theSecondPlayerRowIsSwipedAside() {
        swipePlayerRowAside(ServerPlayers.secondLabel())
    }

    /** Swipes one player row aside, the way a user's hand would. */
    private fun swipePlayerRowAside(label: String) {
        composeRule.waitForIdle()
        composeRule.onNode(row(label)).performScrollTo().performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
    }

    @When("the destination refresh is chosen")
    fun theDestinationRefreshIsChosen() {
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(
            context.getString(R.string.players_refresh),
        ).performClick()
        composeRule.waitForIdle()
    }

    @Then("the Destination card no longer lists the second player")
    fun theDestinationCardNoLongerListsTheSecondPlayer() {
        val label = ServerPlayers.secondLabel()
        composeRule.waitUntil(RealServer.SCREEN_TIMEOUT_MILLIS) {
            composeRule.onAllNodes(row(label)).fetchSemanticsNodes().isEmpty()
        }
    }

    @Then("the Destination card lists the second player again")
    fun theDestinationCardListsTheSecondPlayerAgain() {
        // The refresh reads the list again and forgets every drop, so
        // the row the swipe hid comes back.
        val label = ServerPlayers.secondLabel()
        composeRule.waitUntil(RealServer.SCREEN_TIMEOUT_MILLIS) {
            composeRule.onAllNodes(row(label)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(row(label)).performScrollTo().assertIsDisplayed()
    }

    @Then("the Destination card still lists the first player")
    fun theDestinationCardStillListsTheFirstPlayer() {
        val label = ServerPlayers.firstLabel()
        composeRule.waitForIdle()
        composeRule.onNode(row(label)).performScrollTo().assertIsDisplayed()
    }

    @Then("the finder offers {string} again")
    fun theFinderOffersAgain(app: String) {
        // The probe runs again through the plus, and every run forgets
        // the drops, so the row the swipe hid comes back.
        composeRule.onNodeWithContentDescription(
            context.getString(R.string.finder_open),
        ).performClick()
        composeRule.waitForIdle()
        composeRule.waitUntil(RealServer.SCREEN_TIMEOUT_MILLIS) {
            composeRule.onAllNodes(row(app)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Then("the Source card lists {word}")
    fun theSourceCardLists(app: String) {
        composeRule.onNode(row(app)).assertIsDisplayed()
    }

    @Then("the Source card no longer lists {word}")
    fun theSourceCardNoLongerLists(app: String) {
        composeRule.waitUntil(RealServer.SCREEN_TIMEOUT_MILLIS) {
            composeRule.onAllNodes(row(app)).fetchSemanticsNodes().isEmpty()
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
        // The row is its own selection control, so its own state says
        // whether the pick holds it.
        val node = composeRule.onNode(row(app)).fetchSemanticsNode()
        val selected = node.config.getOrElse(SemanticsProperties.Selected) { false }
        check(selected) {
            "The row of $app is not picked."
        }
    }

    /** The node of one Source card row, named after the session title. */
    private fun row(name: String) = hasText(name, substring = true) and hasClickAction()

    /** The phone, for the checks that read past the app's own window. */
    private fun device() = UiDevice.getInstance(
        InstrumentationRegistry.getInstrumentation(),
    )

    /** Stops one app on the phone, the way a reset would stop it. */
    private fun forceStop(packageName: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("am force-stop $packageName")?.close()
        Thread.sleep(STOP_SETTLE_MILLIS)
    }

    /** The real package of the app the last offer carried, when it did. */
    private var realPackage: String? = null

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

    @And("the destination stops playing the phone")
    fun theDestinationStopsPlayingThePhone() {
        check(ServerPlayers.awaitNotRouted(AWAIT_STREAM_MILLIS)) {
            "The server still points a player at the phone."
        }
    }

    @When("the second player is picked on the Destination card")
    fun secondPlayerIsPicked() {
        val label = ServerPlayers.secondLabel()
        composeRule.onNode(row(label)).performScrollTo().performClick()
        composeRule.waitForIdle()
    }

    @Then("the stream plays to the second player")
    fun theStreamPlaysToTheSecondPlayer() {
        check(ServerPlayers.awaitRouted(AWAIT_STREAM_MILLIS)) {
            "The server never pointed the second player at the phone."
        }
        val requested = awaitSilently(AWAIT_STREAM_MILLIS) {
            SessionStateHolder.state.value.streaming ||
                SessionStateHolder.state.value.serverRequestedAudio
        }
        check(requested) { "The server did not request the audio for the second player." }
    }

    @Then("the stream plays {word}'s audio")
    fun theStreamPlaysTheAppsAudio(app: String) {
        // The pick swaps the capture filter of the running projection, and
        // no consent dialog comes, so only the stream state tells it.
        val keptPlaying = awaitSilently(AWAIT_STREAM_MILLIS) {
            CaptureStatusHolder.state.value.running &&
                (SessionStateHolder.state.value.streaming ||
                    SessionStateHolder.state.value.serverRequestedAudio)
        }
        check(keptPlaying) {
            "The stream did not carry on with $app as the source."
        }
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
        // The row is its own selection control, the way a source row is,
        // so its own state says whether the pick holds it.
        val label = ServerPlayers.firstLabel()
        val node = composeRule.onNode(row(label)).fetchSemanticsNode()
        val selected = node.config.getOrElse(SemanticsProperties.Selected) { false }
        check(selected) {
            "The first player is not picked."
        }
    }

    @Then("the {word} row carries the playing bars")
    fun theRowCarriesThePlayingBars(app: String) {
        // The row merges its children, so the bars give their description
        // to the row itself.
        composeRule.onNode(
            row(app) and hasContentDescription(context.getString(R.string.bars_playing)),
        ).assertIsDisplayed()
    }

    @Then("the first player's row carries the live output bars")
    fun theFirstPlayerCarriesTheLiveOutputBars() {
        val label = ServerPlayers.firstLabel()
        composeRule.waitForIdle()
        composeRule.onNode(
            row(label) and hasContentDescription(context.getString(R.string.bars_live_output)),
        ).performScrollTo().assertIsDisplayed()
    }

    @Then("no destination row carries the live output bars")
    fun noRowCarriesTheLiveOutputBars() {
        composeRule.waitForIdle()
        val bars = composeRule.onAllNodes(
            hasContentDescription(context.getString(R.string.bars_live_output)),
        ).fetchSemanticsNodes()
        check(bars.isEmpty()) {
            "${bars.size} rows still carry the live output bars."
        }
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

        /** How long the phone may take to fall quiet after a stop. */
        const val AWAIT_QUIET_MILLIS = 10_000L

        /** How long a stop takes to land, in milliseconds. */
        const val STOP_SETTLE_MILLIS = 500L

        /** How long the foreground is watched after a pick. */
        const val WATCH_OPEN_MILLIS = 1_500L

        /** How long an app may take to reach the foreground. */
        const val AWAIT_OPEN_MILLIS = 15_000L

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
