package app.gubbins.spindroid.bdd.steps

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import app.gubbins.spindroid.R
import app.gubbins.spindroid.bdd.support.ComposeRuleHolder
import app.gubbins.spindroid.bdd.support.RealServer
import io.cucumber.java.en.And
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When

/**
 * The first onboarding scenario.
 *
 * The scenario needs a real Music Assistant on the network, so the steps
 * search for one before they look at the screen. The name and the address
 * are what the search finds; nothing about the server is hardcoded here.
 */
class OnboardingSteps {
    private val composeRule get() = ComposeRuleHolder.current.composeRule
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Given("a fresh install")
    fun aFreshInstall() {
        // The suite installs the app fresh before it runs, and the search
        // starts with no server, which is what a new install sees.
        RealServer.reset()
    }

    @And("a Music Assistant is present on the network")
    fun aMusicAssistantIsPresent() {
        RealServer.find()
    }

    @When("the app opens")
    fun theAppOpens() {
        composeRule.waitForIdle()
    }

    @Then("the Music Assistant name is shown")
    fun theMusicAssistantNameIsShown() {
        val name = server().name
        // The name is the subtitle of the server row, but other texts
        // say the name too: the pairings the phone keeps, and the
        // status panel. The title beside the subtitle picks the row.
        val subtitle = hasText(name) and hasAnySibling(
            hasText(context.getString(R.string.mass_row_title)),
        )
        composeRule.waitUntil(RealServer.SCREEN_TIMEOUT_MILLIS) {
            composeRule.onAllNodes(subtitle).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(subtitle).assertIsDisplayed()
    }

    private fun server() = RealServer.found
        ?: error("The scenario needs a Music Assistant on the network.")
}
