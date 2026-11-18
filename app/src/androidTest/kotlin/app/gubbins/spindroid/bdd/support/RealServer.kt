package app.gubbins.spindroid.bdd.support

import androidx.test.platform.app.InstrumentationRegistry
import app.gubbins.spindroid.mass.DiscoveredServer
import app.gubbins.spindroid.mass.MassDiscovery
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The Music Assistant that the scenarios find on the network.
 *
 * The app searches for the server itself, and the steps must know which
 * server the app is expected to show. The steps therefore run the same
 * search one time, keep the result, and compare the screen with it.
 *
 * A scenario that needs the server fails here, before it touches the
 * screen, so a missing server gives one clear message instead of a
 * timeout in the middle of the scenario.
 */
object RealServer {
    /** How long a step waits for the server to answer. */
    const val DISCOVERY_TIMEOUT_MILLIS = 10_000L

    /** How long a step waits for the screen to show the server. */
    const val SCREEN_TIMEOUT_MILLIS = 15_000L

    /** The server that the last search found, or null before the search. */
    var found: DiscoveredServer? = null
        private set

    /**
     * Searches the network for the server and keeps the first that answers.
     *
     * @throws IllegalStateException when no server answers in time
     */
    fun find() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val search = MassDiscovery(context)
        search.start()
        try {
            val server = runBlocking {
                withTimeoutOrNull(DISCOVERY_TIMEOUT_MILLIS) {
                    search.server.filterNotNull().first()
                }
            }
            checkNotNull(server) {
                "No Music Assistant answered on the network within " +
                    "${DISCOVERY_TIMEOUT_MILLIS / 1000} seconds. " +
                    "Start the server and connect the phone to the same network."
            }
            found = server
        } finally {
            search.stop()
        }
    }

    /** Forgets the server, because a fresh install starts with none. */
    fun reset() {
        found = null
    }
}
