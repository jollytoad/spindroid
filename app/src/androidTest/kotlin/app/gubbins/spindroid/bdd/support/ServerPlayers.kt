package app.gubbins.spindroid.bdd.support

import androidx.test.platform.app.InstrumentationRegistry
import app.gubbins.spindroid.mass.MassAccountManager
import app.gubbins.spindroid.mass.MassPlayerManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The players that the server offers, as the steps see them.
 *
 * The steps need the names that the server gives its players, and the
 * names come from the server. The support reads them through the same
 * manager that the app screen uses, against the same stored account, so
 * the steps see what the screen sees.
 *
 * The sign in runs on the screen before the first call, so the first
 * calls may come too early. The support retries until the server
 * answers, because a step that fails on timing tells nothing.
 */
object ServerPlayers {
    /** How long the server may take to list its players. */
    private const val TIMEOUT_MILLIS = 15_000L

    /** How long one attempt sleeps before it retries. */
    private const val RETRY_MILLIS = 1_000L

    /** How long a poll sleeps between two reads. */
    private const val POLL_MILLIS = 1_000L

    /**
     * The label of the first player the server offers.
     *
     * @throws IllegalStateException when the server lists no player in time
     */
    fun firstLabel(): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            val label = tryOne(context, label = true, index = 0)
            if (label != null) return label
            Thread.sleep(RETRY_MILLIS)
        }
        error("The server listed no player within ${TIMEOUT_MILLIS / 1000} seconds.")
    }

    /**
     * The identifier of the first player the server offers.
     *
     * @throws IllegalStateException when the server lists no player in time
     */
    fun firstId(): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            val id = tryOne(context, label = false, index = 0)
            if (id != null) return id
            Thread.sleep(RETRY_MILLIS)
        }
        error("The server listed no player within ${TIMEOUT_MILLIS / 1000} seconds.")
    }

    /**
     * The label of the second player the server offers.
     *
     * @throws IllegalStateException when the server lists no player in time
     */
    fun secondLabel(): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            val label = tryOne(context, label = true, index = 1)
            if (label != null) return label
            Thread.sleep(RETRY_MILLIS)
        }
        error("The server listed no player at 1 within ${TIMEOUT_MILLIS / 1000} seconds.")
    }

    /**
     * Whether the server points any player at the phone.
     *
     * The server changes the player on its own clock, so the answer is a
     * poll, not a read. The caller decides how long to wait.
     */
    fun routedToPhone(): Boolean {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val accounts = MassAccountManager(context)
        if (accounts.token == null) return false
        val picker = MassPlayerManager(context, accounts)
        try {
            picker.refresh()
            val state = runBlocking {
                withTimeoutOrNull(TIMEOUT_MILLIS) {
                    picker.state.first { it.players.isNotEmpty() && it.choosing == null }
                }
            } ?: return false
            return state.players.any {
                it.activeSource == picker.sourceUri || it.activeSource == picker.clientId
            }
        } catch (exception: IllegalStateException) {
            return false
        } finally {
            picker.close()
        }
    }

    /**
     * Waits until the server points a player at the phone, or gives up.
     *
     * @param timeoutMillis how long to wait for the answer
     */
    fun awaitRouted(timeoutMillis: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (routedToPhone()) return true
            Thread.sleep(POLL_MILLIS)
        }
        return routedToPhone()
    }

    /**
     * Waits until the server points no player at the phone, or gives up.
     *
     * @param timeoutMillis how long to wait for the answer
     */
    fun awaitNotRouted(timeoutMillis: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (!routedToPhone()) return true
            Thread.sleep(POLL_MILLIS)
        }
        return !routedToPhone()
    }

    /** One attempt to read the player at an index, or null while it fails. */
    private fun tryOne(context: android.content.Context, label: Boolean, index: Int = 0): String? {
        val accounts = MassAccountManager(context)
        if (accounts.token == null) return null
        val picker = MassPlayerManager(context, accounts)
        try {
            picker.refresh()
            val state = runBlocking {
                withTimeoutOrNull(TIMEOUT_MILLIS) {
                    picker.state.first { it.players.isNotEmpty() }
                }
            } ?: return null
            val player = state.players.getOrNull(index) ?: return null
            return if (label) player.label else player.playerId
        } catch (exception: IllegalStateException) {
            return null
        } finally {
            picker.close()
        }
    }
}
