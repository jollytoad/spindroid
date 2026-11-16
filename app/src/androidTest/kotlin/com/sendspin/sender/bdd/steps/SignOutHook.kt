package com.sendspin.sender.bdd.steps

import android.content.Intent
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.sendspin.sender.R
import com.sendspin.sender.bdd.support.ComposeRuleHolder
import com.sendspin.sender.service.SendSpinService
import com.sendspin.sender.session.SessionStateHolder
import io.cucumber.java.Before

/**
 * Signs the app out before every scenario.
 *
 * The account is stored in encrypted preferences, so it survives a
 * scenario. A scenario that signs in must start from the state that a new
 * user sees, so the hook signs out first.
 *
 * A scenario that never signed in has no sign out button, and the hook
 * does nothing then. A stream runs across scenarios, because the service
 * lives in the app process, so the hook stops it too. The view returns to
 * the top, because the scroll of an earlier sign out would leave the cards
 * of the screen out of view.
 */
class SignOutHook {
    @Before
    fun signOut() {
        val rule = ComposeRuleHolder.current.composeRule
        val context = InstrumentationRegistry.getInstrumentation()
            .targetContext
        stopStream(context)
        val label = context.getString(R.string.action_sign_out)
        rule.waitForIdle()
        val found = rule.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()
        if (!found) return
        rule.onAllNodesWithText(label).onFirst().performScrollTo().performClick()
        rule.waitForIdle()
        scrollTop(rule, context)
    }

    /** Stops the stream until it is gone, for a scenario that starts clean. */
    private fun stopStream(context: android.content.Context) {
        if (!SessionStateHolder.running.value) return
        context.stopService(Intent(context, SendSpinService::class.java))
        val deadline = System.currentTimeMillis() + STOP_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (!SessionStateHolder.running.value) return
            Thread.sleep(POLL_MILLIS)
        }
        check(!SessionStateHolder.running.value) { "The stream did not stop in time." }
    }

    /** Brings the screen back to the top of the scroll. */
    private fun scrollTop(rule: androidx.compose.ui.test.junit4.ComposeContentTestRule, context: android.content.Context) {
        rule.onAllNodesWithText(context.getString(R.string.title_source))
            .onFirst()
            .performScrollTo()
        rule.waitForIdle()
    }

    private companion object {
        /** How long the service may take to stop. */
        const val STOP_MILLIS = 10_000L

        /** How long a wait sleeps between two checks. */
        const val POLL_MILLIS = 250L
    }
}

