package app.gubbins.spindroid

import app.gubbins.spindroid.capture.SessionApps
import org.junit.jupiter.api.Test

/**
 * The rule the finder follows with the drops.
 *
 * A swipe is a choice about the card, so every run of the finder keeps
 * the drops: the rows a swipe hid stay hidden. Only the show-all
 * control forgets them, because it is the one view of every installed
 * media app the user asked for.
 */
class SessionAppsFinderTest {

    @Test
    fun `the finder keeps the drops and the show-all control forgets them`() {
        SessionApps.offerInstalled("Radio", "Mellow")
        SessionApps.runFinder()
        SessionApps.discard("radio")
        check(SessionApps.apps.value.map { it.label } == listOf("Mellow")) {
            "The swipe must hide the row, which gives ${SessionApps.apps.value.map { it.label }}."
        }
        SessionApps.runFinder()
        check(SessionApps.apps.value.map { it.label } == listOf("Mellow")) {
            "A run of the finder must keep the drop, which gives ${SessionApps.apps.value.map { it.label }}."
        }
        SessionApps.showAllApps()
        check(SessionApps.apps.value.map { it.label } == listOf("Mellow", "Radio")) {
            "The show-all control must offer every app again, which gives ${SessionApps.apps.value.map { it.label }}."
        }
        SessionApps.resetDiscards()
    }
}
