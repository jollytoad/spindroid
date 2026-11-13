package com.sendspin.sender.bdd.steps

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import com.sendspin.sender.bdd.support.ComposeRuleHolder
import io.cucumber.datatable.DataTable
import io.cucumber.java.en.And
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When

/**
 * The first onboarding scenario.
 *
 * The steps that set up the network and the install are placeholders for the
 * harness. The assertion at the end is the behavior that the feature asks
 * for.
 */
class OnboardingSteps {
    private val composeRule get() = ComposeRuleHolder.current.composeRule

    @Given("a fresh install")
    fun aFreshInstall() {
        // The test APK is installed fresh before the suite runs.
    }

    @And("a Music Assistant is present on the network")
    fun aMusicAssistantIsPresent() {
        // Discovery is not faked yet. This harness only needs to run the
        // scenario and show the missing onboarding behavior.
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
