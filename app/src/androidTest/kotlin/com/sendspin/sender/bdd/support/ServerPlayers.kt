package com.sendspin.sender.bdd.support

import androidx.test.platform.app.InstrumentationRegistry
import com.sendspin.sender.mass.MassAccountManager
import com.sendspin.sender.mass.MassPlayerManager
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

    /**
     * The label of the first player the server offers.
     *
     * @throws IllegalStateException when the server lists no player in time
     */
    fun firstLabel(): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            val label = tryOne(context)
            if (label != null) return label
            Thread.sleep(RETRY_MILLIS)
        }
        error("The server listed no player within ${TIMEOUT_MILLIS / 1000} seconds.")
    }

    /** One attempt to read the first player, or null while it fails. */
    private fun tryOne(context: android.content.Context): String? {
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
            return state.players.first().label
        } catch (exception: IllegalStateException) {
            return null
        } finally {
            picker.close()
        }
    }
}
