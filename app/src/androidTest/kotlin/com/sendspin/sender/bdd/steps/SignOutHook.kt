package com.sendspin.sender.bdd.steps

import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.sendspin.sender.R
import com.sendspin.sender.bdd.support.ComposeRuleHolder
import io.cucumber.java.Before

/**
 * Signs the app out before every scenario.
 *
 * The account is stored in encrypted preferences, so it survives a
 * scenario. A scenario that signs in must start from the state that a new
 * user sees, so the hook signs out first.
 *
 * A scenario that never signed in has no sign out button, and the hook
 * does nothing then.
 */
class SignOutHook {
    @Before
    fun signOut() {
        val rule = ComposeRuleHolder.current.composeRule
        val label = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .getString(R.string.action_sign_out)
        rule.waitForIdle()
        val found = rule.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()
        if (!found) return
        rule.onAllNodesWithText(label).onFirst().performScrollTo().performClick()
        rule.waitForIdle()
    }
}
