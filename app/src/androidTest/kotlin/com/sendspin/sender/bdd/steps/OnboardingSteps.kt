package com.sendspin.sender.bdd.steps

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import com.sendspin.sender.bdd.support.ComposeRuleHolder
import com.sendspin.sender.bdd.support.FakeDiscoveryHolder
import com.sendspin.sender.mass.DiscoveredServer
import io.cucumber.datatable.DataTable
import io.cucumber.java.en.And
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When

/**
 * The first onboarding scenario.
 *
 * The app reads Music Assistant from the network. A test cannot rely on
 * mDNS, so the steps publish a fake server with the name that the scenario
 * asserts. The screen handles the fake as it handles a real find.
 */
class OnboardingSteps {
    private val composeRule get() = ComposeRuleHolder.current.composeRule

    @Given("a fresh install")
    fun aFreshInstall() {
        // The test APK is installed fresh before the suite runs. The fake
        // search starts with no server, which is what a new install sees.
        FakeDiscoveryHolder.discovery.reset()
    }

    @And("a Music Assistant is present on the network")
    fun aMusicAssistantIsPresent() {
        // The name is the one that the scenario asserts below. A real
        // install reads it from the mDNS record.
        FakeDiscoveryHolder.discovery.publish(
            DiscoveredServer(
                name = "Attic Music Assistant",
                url = "http://10.0.2.2:8095",
            ),
        )
    }

    @When("the app opens")
    fun theAppOpens() {
        composeRule.waitForIdle()
    }

    @Then("the Music Assistant name is shown")
    fun theMusicAssistantNameIsShown() {
        composeRule.onNodeWithText("Attic Music Assistant").assertIsDisplayed()
    }

    @And("connection options are presented:")
    fun connectionOptionsArePresented(options: DataTable) {
        options.asLists().forEach { row ->
            composeRule.onNodeWithText(row[0]).assertIsDisplayed()
        }
    }
}
