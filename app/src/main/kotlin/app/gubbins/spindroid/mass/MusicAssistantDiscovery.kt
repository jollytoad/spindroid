package app.gubbins.spindroid.mass

import kotlinx.coroutines.flow.StateFlow

/**
 * One Music Assistant that the phone found on the local network.
 *
 * The name comes from the mDNS record, and the user sees it on the screen.
 * The address is the base address of the web interface, such as
 * `http://192.168.8.242:8095`.
 */
data class DiscoveredServer(
    val name: String,
    val url: String,
)

/**
 * Finds Music Assistant on the local network.
 *
 * The screen shows what the search found, and the search outlives a single
 * call, so the result is a state flow.
 */
interface MusicAssistantDiscovery {
    /** The server that answered, or null while the search runs. */
    val server: StateFlow<DiscoveredServer?>

    /** The reason the search failed, or null when it has not failed. */
    val error: StateFlow<String?>

    /** Starts the search. */
    fun start()

    /** Stops the search. */
    fun stop()
}
