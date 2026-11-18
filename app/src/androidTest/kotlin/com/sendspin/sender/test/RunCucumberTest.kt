package com.sendspin.sender.test

import io.cucumber.junit.CucumberOptions

/**
 * The entry point for the Gherkin suite.
 *
 * Cucumber-Android finds this class in the test application package. The
 * feature files are assets of the test APK, so they never ship with the app.
 */
@CucumberOptions(
    features = ["source.feature", "two-card-sender.feature", "add-sources.feature"],
    glue = ["com.sendspin.sender.bdd"],
)
class RunCucumberTest
