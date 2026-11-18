package app.gubbins.spindroid.bdd.steps

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.gubbins.spindroid.bdd.support.ComposeRuleHolder
import app.gubbins.spindroid.bdd.support.RealServer
import io.cucumber.datatable.DataTable
import io.cucumber.java.en.And
import io.cucumber.java.en.Then
import io.cucumber.java.en.When

/**
 * The sign in scenario.
 *
 * The first page offers the sign in as an option. The page that the option
 * opens holds the form, and the form holds the fields and the two buttons
 * that the scenario names.
 *
 * The address in the Server field is the one that the real search found,
 * because the app fills the field from its own search.
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
     * The app searches for the server in parallel with this step, so the
     * field can be empty for a moment. The step waits for the app to fill
     * it with the address that the real search found.
     */
    @And("the Server field is pre-populated with the Music Assistant server URL")
    fun theServerFieldIsPrePopulatedWithTheMusicAssistantUrl() {
        val server = RealServer.found
            ?: error("The scenario needs a Music Assistant on the network.")
        val field = hasSetTextAction() and hasText(server.url)
        composeRule.waitUntil(RealServer.SCREEN_TIMEOUT_MILLIS) {
            composeRule.onAllNodes(field).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(field).assertIsDisplayed()
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
