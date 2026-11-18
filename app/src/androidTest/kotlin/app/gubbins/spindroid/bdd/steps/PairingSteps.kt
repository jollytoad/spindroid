package app.gubbins.spindroid.bdd.steps

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import app.gubbins.spindroid.R
import app.gubbins.spindroid.bdd.support.AdminApi
import app.gubbins.spindroid.bdd.support.ComposeRuleHolder
import app.gubbins.spindroid.bdd.support.Credentials
import app.gubbins.spindroid.bdd.support.RealServer
import app.gubbins.spindroid.service.SpindroidService
import app.gubbins.spindroid.session.PairingServiceHolder
import app.gubbins.spindroid.session.SessionStateHolder
import app.gubbins.spindroid.storage.EncryptedKeyStore
import app.gubbins.sendspin.session.SourceSessionState
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The pairing that follows the sign in.
 *
 * The scenario signs in with an administrator, because only an
 * administrator may remove the pairing that an earlier run left on the
 * server. The phone must start unpaired, so the app has to pair it again.
 *
 * The server pairs the phone over the session that the phone opens before
 * the user allows the screen capture. The scenario therefore starts only
 * the listener, waits for the session, and then checks the screen.
 */
class PairingSteps {
    private val composeRule get() = ComposeRuleHolder.current.composeRule
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Skips the scenario when the machine has no administrator account.
     *
     * The account is a secret, so it is not in the repository. The step
     * signs in once, and the later steps reuse the token.
     */
    @Given("a Music Assistant administrator account is available")
    fun anAdministratorAccountIsAvailable() {
        Credentials.require()
        AdminApi.signIn(server().url)
    }

    /**
     * Removes the pairing that an earlier run left behind.
     *
     * The server can only remove the pairing of a client that is connected,
     * so the listener runs first. The phone keeps its identity, because the
     * scenario must pair the same key again, not a new one.
     */
    @Given("the phone is not paired with the Music Assistant")
    fun thePhoneIsNotPaired() {
        val server = server()
        val token = AdminApi.signIn(server.url)
        val keys = EncryptedKeyStore(context)
        val service = Intent(context, SpindroidService::class.java)
        SpindroidService.startPairing(context)
        try {
            val session = awaitSession()
            val serverId = session.serverId
                ?: error("Music Assistant connected but the phone did not learn its identifier.")
            val pairedBefore = keys.longTermRecords().any { it.serverId == serverId }
            try {
                val player = AdminApi.awaitPlayer(server.url, token)
                AdminApi.unpair(server.url, token, player.playerId)
            } catch (exception: IllegalStateException) {
                // A phone that was never paired has nothing to remove. Any
                // other failure would hide a real problem, so it stays.
                if (pairedBefore) throw exception
            }
            awaitUntil(AWAIT_FORGET_MILLIS) {
                keys.longTermRecords().none { it.serverId == serverId }
            }
            // A record that the server does not know about would block the
            // new pairing. The phone forgets it, so the scenario starts clean.
            keys.longTermRecords()
                .filter { it.serverId == serverId }
                .forEach { keys.removeLongTerm(it.serverId) }
        } finally {
            context.stopService(service)
            awaitUntil(AWAIT_STOP_MILLIS) {
                !SessionStateHolder.running.value && !PairingServiceHolder.running.value
            }
            SessionStateHolder.reset()
        }
    }

    /** The fields take the account that the machine supplied. */
    @When("the sign in form is filled with the Music Assistant account")
    fun theSignInFormIsFilledWithTheAccount() {
        Credentials.require()
        val user = Credentials.userName
            ?: error("The scenario needs -Pbdd.ma.user, or bdd/credentials.properties.")
        val password = Credentials.password
            ?: error("The scenario needs -Pbdd.ma.password, or bdd/credentials.properties.")
        fill(label = context.getString(R.string.label_account_username), value = user)
        fill(label = context.getString(R.string.label_account_password), value = password)
    }

    /** The form sends the account to the server. */
    @When("{string} is pressed")
    fun buttonIsPressed(button: String) {
        composeRule.onNode(hasText(button) and hasClickAction()).performClick()
        composeRule.waitForIdle()
    }

    /**
     * The pairing section names the pairing that the app made on its own.
     *
     * The server may take a while to pair the phone, so the step waits
     * longer than a step that only reads the screen.
     */
    @Then("the pairing section says that the phone is paired")
    fun thePairingSectionSaysThatThePhoneIsPaired() {
        val paired = context.getString(R.string.pairing_paired)
        composeRule.waitUntil(PAIRING_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText(paired).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(paired).performScrollTo().assertIsDisplayed()
    }

    /** Types a value into the field with the given label. */
    private fun fill(label: String, value: String) {
        composeRule.onNode(hasSetTextAction() and hasText(label)).performTextInput(value)
    }

    /** Waits for a session with a server. */
    private fun awaitSession(): SourceSessionState {
        val session = runBlocking {
            withTimeoutOrNull(SESSION_TIMEOUT_MILLIS) {
                SessionStateHolder.state.first { it.serverId != null }
            }
        }
        return session ?: error(
            "The phone did not start a session with Music Assistant within " +
                "${SESSION_TIMEOUT_MILLIS / 1000} seconds. The phone must be on the same network.",
        )
    }

    private fun server() = RealServer.found
        ?: error("The scenario needs a Music Assistant on the network.")

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
        /** How long the phone may take to learn the server. */
        const val SESSION_TIMEOUT_MILLIS = 20_000L

        /** How long the pairing may take on the server. */
        const val PAIRING_TIMEOUT_MILLIS = 120_000L

        /** How long the phone may take to forget the old pairing. */
        const val AWAIT_FORGET_MILLIS = 10_000L

        /** How long the service may take to stop. */
        const val AWAIT_STOP_MILLIS = 10_000L

        /** How long a wait sleeps between two checks. */
        const val POLL_MILLIS = 250L
    }
}
