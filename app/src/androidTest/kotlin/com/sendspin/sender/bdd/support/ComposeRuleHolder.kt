package com.sendspin.sender.bdd.support

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.sendspin.sender.AppContainer
import com.sendspin.sender.MainActivity
import io.cucumber.junit.WithJunitRule
import org.junit.Rule

/**
 * Holds the Compose rule for the Cucumber scenarios.
 *
 * Cucumber creates a fresh instance for each scenario, so the rule launches
 * the app before the first step runs and closes it afterwards.
 *
 * The fake search is installed before the rule is applied, because the rule
 * starts the activity, and the activity builds its search when it starts.
 */
@WithJunitRule
class ComposeRuleHolder {
    init {
        AppContainer.discoveryFactory = { FakeDiscoveryHolder.discovery }
    }

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    init {
        current = this
    }

    companion object {
        lateinit var current: ComposeRuleHolder
            private set
    }
}
