package app.gubbins.spindroid

import app.gubbins.spindroid.capture.SessionApps
import org.junit.jupiter.api.Test

/**
 * The rule the plus follows with the drops.
 *
 * The plus is the one view of every installed media app, so a swipe
 * hides a row only until the next run of the plus: each run forgets
 * the drops and offers the whole phone again.
 */
class SessionAppsFinderTest {

    @Test
    fun `the plus forgets the drops and shows every app again`() {
        SessionApps.offerInstalled("Radio", "Mellow")
        SessionApps.runFinder()
        SessionApps.discard("radio")
        check(SessionApps.apps.value.map { it.label } == listOf("Mellow")) {
            "The swipe must hide the row, which gives ${SessionApps.apps.value.map { it.label }}."
        }
        SessionApps.runFinder()
        check(SessionApps.apps.value.map { it.label } == listOf("Mellow", "Radio")) {
            "The plus must offer every app again, which gives ${SessionApps.apps.value.map { it.label }}."
        }
        SessionApps.resetDiscards()
    }
}
