package app.gubbins.spindroid.mass

import android.content.Context
import app.gubbins.mass.LoginResult
import app.gubbins.mass.MusicAssistantClient
import app.gubbins.spindroid.discovery.DeviceName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    private val appContext = context.applicationContext
    private val store = MassAccountStore(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(readState())

    /**
     * The sign ins that the app made.
     *
     * The pairing starts from this flow, because a sign in is the only
     * moment that the app learns of a new account. The pairing needs the
     * account, so it cannot read the stored account before the sign in
     * finishes.
     */
    private val mutableSignInEvents = MutableSharedFlow<MassSignInEvent>(extraBufferCapacity = 1)

    /** The state of the account. */
    val state: StateFlow<MassAccountState> = mutableState.asStateFlow()

    /** The sign ins that the app made. */
    val signInEvents: SharedFlow<MassSignInEvent> = mutableSignInEvents.asSharedFlow()

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
                    val administrator = result.role == ROLE_ADMIN
                    store.token = result.token
                    store.username = result.username
                    store.displayName = result.displayName
                    store.role = result.role
                    publish(
                        mutableState.value.copy(
                            signingIn = false,
                            error = null,
                            username = result.username,
                            displayName = result.displayName,
                            administrator = administrator,
                        ),
                    )
                    mutableSignInEvents.emit(
                        MassSignInEvent(
                            serverUrl = serverUrl,
                            token = result.token,
                            administrator = administrator,
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
        administrator = store.role == ROLE_ADMIN,
    )

    /** Publishes a new state. */
    private fun publish(newState: MassAccountState) {
        mutableState.value = newState
    }

    /**
     * The name that the token carries.
     *
     * The server shows the name in its list of tokens, so the name the
     * phone carries in its settings tells two phones apart.
     */
    private fun deviceName(): String = DeviceName.of(appContext)

    private companion object {
        /** The role that may change the server. */
        const val ROLE_ADMIN = "admin"
    }
}
