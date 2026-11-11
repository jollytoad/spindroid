package com.sendspin.sender.mass

import android.content.Context
import android.os.Build
import com.sendspin.mass.LoginResult
import com.sendspin.mass.MusicAssistantClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Music Assistant account that the phone signed in to.
 *
 * The sign in reaches the server over the network, so it runs on a thread
 * of its own. The manager holds the state that the screen shows and the
 * token that the later commands authenticate with.
 */
class MassAccountManager(
    context: Context,
) {
    private val store = MassAccountStore(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(readState())

    /** The state of the account. */
    val state: StateFlow<MassAccountState> = mutableState.asStateFlow()

    /**
     * The token of the signed in account.
     *
     * It is null when no account is signed in. The commands that come after
     * the sign in authenticate with it.
     */
    val token: String? get() = store.token

    /**
     * Signs in to the server.
     *
     * @param serverUrl the base address of the server
     * @param username the name of the account
     * @param password the password of the account
     */
    fun signIn(serverUrl: String, username: String, password: String) {
        if (serverUrl.isBlank()) {
            publish(mutableState.value.copy(error = "The server address is empty"))
            return
        }
        scope.launch {
            publish(mutableState.value.copy(signingIn = true, error = null))
            val result = withContext(Dispatchers.IO) {
                MusicAssistantClient(serverUrl).login(username, password, deviceName())
            }
            when (result) {
                is LoginResult.Success -> {
                    store.token = result.token
                    store.username = result.username
                    store.displayName = result.displayName
                    publish(
                        mutableState.value.copy(
                            signingIn = false,
                            error = null,
                            username = result.username,
                            displayName = result.displayName,
                        ),
                    )
                }

                is LoginResult.Failure -> {
                    publish(mutableState.value.copy(signingIn = false, error = result.reason))
                }
            }
        }
    }

    /** Forgets the account. */
    fun signOut() {
        store.clear()
        publish(readState())
    }

    /**
     * Sets the address of the server.
     *
     * The search fills the address in, but the user may type another one.
     * A typed address wins, so the search never overwrites it.
     */
    fun setServerUrl(serverUrl: String, edited: Boolean) {
        if (serverUrl.isBlank()) return
        if (edited) {
            publish(mutableState.value.copy(serverUrl = serverUrl))
            store.serverUrl = serverUrl
            return
        }
        if (mutableState.value.serverUrl.isBlank()) {
            publish(mutableState.value.copy(serverUrl = serverUrl))
            store.serverUrl = serverUrl
        }
    }

    /** Stops the work that is running. */
    fun close() {
        scope.cancel()
    }

    /** The state the stored account gives. */
    private fun readState(): MassAccountState = MassAccountState(
        serverUrl = store.serverUrl.orEmpty(),
        username = store.username,
        displayName = store.displayName,
    )

    /** Publishes a new state. */
    private fun publish(newState: MassAccountState) {
        mutableState.value = newState
    }

    /**
     * The name that the token carries.
     *
     * The server shows the name in its list of tokens, so the model of the
     * phone tells two phones apart.
     */
    private fun deviceName(): String = "SendSpin on ${Build.MODEL}"
}
