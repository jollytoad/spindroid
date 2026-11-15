package com.sendspin.sender.bdd.steps

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.sendspin.sender.bdd.support.ComposeRuleHolder
import com.sendspin.sender.bdd.support.FakeDiscoveryHolder
import io.cucumber.datatable.DataTable
import io.cucumber.java.en.And
import io.cucumber.java.en.Then
import io.cucumber.java.en.When

/**
 * The sign in scenario.
 *
 * The scenario states what the app must do, so the steps below state it
 * too. The sign in page is not built yet, and these steps fail until it is.
 *
 * The first page offers the sign in as an option. The page that the option
 * opens holds the form, and the form holds the fields and the two buttons
 * that the scenario names.
 */
class SignInSteps {
    private val composeRule get() = ComposeRuleHolder.current.composeRule

    /** The option moves the app to the sign in page. */
    @When("{string} is chosen")
    fun optionIsChosen(option: String) {
        composeRule.onNodeWithText(option).performClick()
        composeRule.waitForIdle()
    }

    /**
     * The sign in page has its own title, and the title is what tells the
     * page apart from the page that the option sits on.
     */
    @Then("a sign in page is shown")
    fun aSignInPageIsShown() {
        composeRule.onNodeWithText(SIGN_IN_TITLE).assertIsDisplayed()
    }

    /**
     * A form is a set of fields that take text, so the page must hold at
     * least one field. The fields are named in the next step.
     */
    @And("the page has a sign in form")
    fun thePageHasASignInForm() {
        composeRule.onAllNodes(hasSetTextAction()).onFirst().assertIsDisplayed()
    }

    /** The form names every field, so the user knows what to type. */
    @And("the form has the fields:")
    fun theFormHasTheFields(fields: DataTable) {
        fields.asLists().forEach { row ->
            composeRule.onNodeWithText(row[0]).assertIsDisplayed()
        }
    }

    /**
     * The address comes from the search, so the user does not type it.
     *
     * The field holds the address that the fake search published, and that
     * is the address that a real search would find too.
     */
    @And("the Server field is pre-populated with the Music Assistant server URL")
    fun theServerFieldIsPrePopulatedWithTheMusicAssistantUrl() {
        val server = FakeDiscoveryHolder.discovery.server.value
            ?: error("The scenario needs a Music Assistant on the network.")
        composeRule.onNode(hasSetTextAction() and hasText(server.url)).assertIsDisplayed()
    }

    /** The buttons are the two ways out of the form. */
    @And("these buttons are presented:")
    fun theseButtonsArePresented(buttons: DataTable) {
        buttons.asLists().forEach { row ->
            composeRule.onNode(hasText(row[0]) and hasClickAction()).assertIsDisplayed()
        }
    }

    private companion object {
        /**
         * The title that the test expects on the sign in page.
         *
         * The page does not exist yet, so the test names the title. Change
         * this value, and the step above, if the page gets another title.
         */
        const val SIGN_IN_TITLE = "Sign in to Music Assistant"
    }
}
