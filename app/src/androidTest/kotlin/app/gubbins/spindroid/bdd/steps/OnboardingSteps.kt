package app.gubbins.spindroid.bdd.steps

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import app.gubbins.spindroid.bdd.support.ComposeRuleHolder
import app.gubbins.spindroid.bdd.support.RealServer
import io.cucumber.datatable.DataTable
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
        composeRule.waitUntil(RealServer.SCREEN_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(name).assertIsDisplayed()
    }

    @And("connection options are presented:")
    fun connectionOptionsArePresented(options: DataTable) {
        options.asLists().forEach { row ->
            composeRule.onNodeWithText(row[0]).assertIsDisplayed()
        }
    }

    private fun server() = RealServer.found
        ?: error("The scenario needs a Music Assistant on the network.")
}
