package com.sendspin.sender.mass

import android.content.Context
import android.util.Log
import com.sendspin.mass.MassPlayer
import com.sendspin.mass.MusicAssistantClient
import com.sendspin.mass.PlayersResult
import com.sendspin.mass.SelectSourceResult
import com.sendspin.protocol.crypto.Base64Url
import com.sendspin.protocol.crypto.X25519
import com.sendspin.sender.session.SessionStateHolder
import com.sendspin.sender.storage.EncryptedKeyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The player picker.
 *
 * The SendSpin protocol has no way to name a target player, so the choice
 * of where the sound goes belongs to Music Assistant. The picker asks the
 * server which players it has, and tells the server which one should play
 * the phone.
 *
 * The phone knows the name that the server uses for it. The server lists
 * the phone under the client's own id, and the id is the base64url of the
 * client's public key, which the phone built. So the source address is
 * written here without asking the server for it.
 */
class MassPlayerManager(
    context: Context,
    private val accounts: MassAccountManager,
) {
    private val keys = EncryptedKeyStore(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(MassPlayerState())

    /** The state of the picker. */
    val state: StateFlow<MassPlayerState> = mutableState.asStateFlow()

    /**
     * The id that the server knows the phone by.
     *
     * It is the same id that the session sends in its hello.
     */
    val clientId: String = Base64Url.encode(X25519.publicKey(keys.staticPrivateKey))

    /**
     * The address of the phone as a source.
     *
     * The server takes this address to mean the live audio of the phone.
     */
    val sourceUri: String = "$SOURCE_SCHEME$clientId"

    /**
     * Reads the list of players from the server.
     *
     * The list is read again after every choice, so the screen shows what
     * the server really does and not what the phone asked for.
     */
    fun refresh() {
        val token = accounts.token
        if (token == null) {
            publish(mutableState.value.copy(error = "Sign in to Music Assistant first"))
            return
        }
        val serverUrl = accounts.state.value.serverUrl
        if (serverUrl.isBlank()) {
            publish(mutableState.value.copy(error = "The address of Music Assistant is not known yet"))
            return
        }
        scope.launch {
            publish(mutableState.value.copy(loading = true, error = null))
            val result = withContext(Dispatchers.IO) {
                MusicAssistantClient(serverUrl).listPlayers(token)
            }
            when (result) {
                is PlayersResult.Success -> publish(
                    mutableState.value.copy(
                        loading = false,
                        error = null,
                        players = targets(result.players),
                        allPlayers = result.players,
                    ),
                )

                is PlayersResult.Failure -> publish(
                    mutableState.value.copy(loading = false, error = result.reason),
                )
            }
        }
    }

    /**
     * Points a player at the phone.
     *
     * The server answers this command with nothing, and it answers the same
     * way when it did nothing at all. So the list is read again, and the
     * screen shows what the server really did.
     *
     * The choice works while the phone listens: the server connects to the
     * phone when a player points at it, so the session pairs after the
     * choice, not before it.
     *
     * @param playerId the player that should play the phone
     */
    fun choose(playerId: String) {
        val token = accounts.token ?: return
        val serverUrl = accounts.state.value.serverUrl
        if (serverUrl.isBlank()) {
            publish(mutableState.value.copy(error = "The address of Music Assistant is not known yet"))
            return
        }
        if (!SessionStateHolder.running.value) {
            // The server cannot reach a phone that does not listen, and the
            // Connect control puts the listener up.
            publish(mutableState.value.copy(error = REASON_NOT_LIVE))
            return
        }
        scope.launch {
            publish(mutableState.value.copy(choosing = playerId, error = null))
            val client = MusicAssistantClient(serverUrl)
            val result = withContext(Dispatchers.IO) {
                client.selectSource(token, playerId, sourceUri)
            }
            Log.i(TAG, "The source of $playerId went to the server with $result")
            if (result is SelectSourceResult.Failure) {
                publish(mutableState.value.copy(choosing = null, error = result.reason))
                return@launch
            }
            // The server changes the player while the command runs, but the
            // player tells the server about it a moment later.
            delay(SETTLE_MILLIS)
            val players = withContext(Dispatchers.IO) { client.listPlayers(token) }
            when (players) {
                is PlayersResult.Failure -> publish(
                    mutableState.value.copy(choosing = null, error = players.reason),
                )

                is PlayersResult.Success -> publish(
                    mutableState.value.copy(
                        choosing = null,
                        error = errorOf(playerId, players.players),
                        players = targets(players.players),
                        allPlayers = players.players,
                    ),
                )
            }
        }
    }

    /** Stops the work that is running. */
    fun close() {
        scope.cancel()
    }

    /**
     * The places the sound can go.
     *
     * The server sends every player it knows about: the ones that make
     * sound, the groups they are in, and the devices that only take sound
     * in, such as the phone itself. The picker shows one row for each
     * place the sound can go, so the members of a group are not rows of
     * their own.
     */
    private fun targets(players: List<MassPlayer>): List<MassPlayer> {
        val known = players.map { it.playerId }.toSet()
        return players
            .filter { it.playsAudio }
            .filter { it.enabled && !it.hideInUi && !it.isPrivate && !it.needsSetup }
            .filter { it.activeGroup == null || it.activeGroup !in known }
            .filter { it.syncedTo == null || it.syncedTo !in known }
            .sortedWith(compareByDescending<MassPlayer> { it.available }.thenBy { it.label.lowercase() })
    }

    /**
     * The reason the choice failed, or nothing when it worked.
     *
     * The server keeps the chosen source on the player, and that is how the
     * screen knows where the sound goes.
     */
    private fun errorOf(playerId: String, players: List<MassPlayer>): String? {
        val target = players.firstOrNull { it.playerId == playerId } ?: return null
        val routed = target.activeSource == sourceUri || target.activeSource == clientId
        if (routed) return null
        return "The server did not point ${target.label} at the phone"
    }

    /** Publishes a new state. */
    private fun publish(newState: MassPlayerState) {
        mutableState.value = newState
    }

    private companion object {
        private const val TAG = "SendSpin"

        /** The address of a live source of the phone. */
        const val SOURCE_SCHEME = "sendspin_source://audio_source/"

        /** The reason the server cannot play the phone yet. */
        const val REASON_NOT_LIVE =
            "The phone is not streaming yet, so press Connect first"

        /**
         * How long the app waits for the server to tell about the change.
         *
         * The server changes the player while the command runs, but the
         * player tells the server about it a moment later.
         */
        const val SETTLE_MILLIS = 500L
    }
}
