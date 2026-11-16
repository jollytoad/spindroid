package com.sendspin.sender.bdd.support

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

/**
 * The media projection consent dialog.
 *
 * The dialog belongs to the system, not to the app, so the Compose rule
 * cannot reach it. The UI Automator device reads the whole screen, which
 * includes the dialog that the system puts in front.
 */
object Consent {
    /** How long the system may take to show the dialog. */
    private const val TIMEOUT_MILLIS = 10_000L

    /** The label of the button that allows the capture. */
    private const val ALLOW = "Start now"

    private fun device(): UiDevice =
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    /** Waits until the dialog is in front, and fails when it never comes. */
    fun waitPresented() {
        val shown = device().wait(Until.hasObject(By.textContains(ALLOW)), TIMEOUT_MILLIS)
        check(shown) { "The media projection consent dialog did not show in time." }
    }

    /**
     * Chooses one of the dialog's answers.
     *
     * @param option the label of the button, such as the allow label
     */
    fun choose(option: String) {
        val button = device().findObject(By.text(option))
            ?: error("The consent dialog has no button that reads $option.")
        button.click()
        device().wait(Until.gone(By.text(option)), TIMEOUT_MILLIS)
    }
}
