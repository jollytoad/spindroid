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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    /**
     * The address of the phone as a source.
     *
     * Music Assistant claims the phone's live audio under this scheme
     * address: it resolves to the device that the pairing created, and the
     * claim that follows the selection asks the phone to stream. The
     * command can refuse the claim while the phone is still settling, so
     * the caller retries, and a plain device select is NOT a substitute:
     * it answers with success and never claims.
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
    /** The coroutine that repeats the routing of the picked player. */
    private var router: Job? = null

    /** The player that the server last pointed at the phone. */
    private var routedPlayerId: String? = null

    /**
     * Keeps the picked player pointed at the phone and playing.
     *
     * The router runs on the manager's own scope, until [stopRouting]:
     * the select and the play retry while the phone listens, because the
     * server claims the source on its own clock, and the play press stops
     * once the audio flows. A pause that the user makes after the sound
     * came is never fought.
     *
     * @param playerId the player that should play the phone
     */
    fun startRouting(playerId: String) {
        router?.cancel()
        val previousPlayerId = routedPlayerId
        routedPlayerId = playerId
        if (previousPlayerId != null && previousPlayerId != playerId) {
            // The sound goes to the new player alone, so the old one is
            // stopped rather than left playing a stream it no longer owns.
            scope.launch {
                withContext(Dispatchers.IO) {
                    stopOnServer(previousPlayerId)
                }
            }
        }
        router = scope.launch {
            var played = false
            var attempts = 0
            while (isActive) {
                delay(2_000)
                if (SessionStateHolder.running.value != true) {
                    continue
                }
                val playing = SessionStateHolder.state.value.streaming ||
                    SessionStateHolder.state.value.serverRequestedAudio
                if (playing) played = true
                if (played || attempts >= ROUTE_ATTEMPTS) continue
                attempts += 1
                withContext(Dispatchers.IO) {
                    if (attempts % 2 == 0) {
                        choose(playerId)
                    } else {
                        playPlayer(playerId)?.let { reason ->
                            Log.i(TAG, "The player did not start: $reason")
                        }
                    }
                }
            }
        }
    }

    /**
     * Stops the routing, and quiets the player that played the phone.
     *
     * The server keeps the routing that a disconnect does not clear, so
     * the stopped player would otherwise carry on with the lost stream:
     * the command stops it together with the phone side.
     */
    fun stopRouting() {
        router?.cancel()
        router = null
        val id = routedPlayerId
        routedPlayerId = null
        if (id != null) {
            scope.launch {
                withContext(Dispatchers.IO) {
                    stopOnServer(id)
                }
            }
        }
    }

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
            if (result is SelectSourceResult.Success) {
                routedPlayerId = playerId
            }
            if (result is SelectSourceResult.Failure) {
                publish(mutableState.value.copy(choosing = null, error = result.reason))
                return@launch
            }
            // The routed player sits idle, and an idle player asks the
            // phone for no audio. The app presses play on it, the way the
            // source screen of Music Assistant does.
            val playResult = withContext(Dispatchers.IO) {
                client.playPlayer(token, playerId)
            }
            if (playResult != null) {
                Log.i(TAG, "The player did not start: $playResult")
                publish(mutableState.value.copy(choosing = null, error = playResult))
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

    /**
     * Starts the transport of one player.
     *
     * A player that points at the phone but sits idle asks the phone for
     * no audio. The app presses play on it, the way the source screen of
     * Music Assistant does.
     *
     * The call blocks on the network, so the caller runs it off the main
     * thread.
     *
     * @param playerId the player that should play
     * @return nothing, or the reason the server gave
     */
    fun playPlayer(playerId: String): String? {
        val token = accounts.token ?: return "Sign in to Music Assistant first"
        val serverUrl = accounts.state.value.serverUrl
        if (serverUrl.isBlank()) {
            return "The address of Music Assistant is not known yet"
        }
        return MusicAssistantClient(serverUrl).playerCommand(token, playerId, "play")
    }

    /**
     * Sends one transport command to the player the screen picked.
     *
     * The call blocks on the network, so the caller runs it off the main
     * thread.
     *
     * @param playerId the player that receives the command
     * @param command the transport command, such as stop
     * @return nothing, or the reason the server gave
     */
    private fun playerCommand(playerId: String, command: String): String? {
        val token = accounts.token ?: return "Sign in to Music Assistant first"
        val serverUrl = accounts.state.value.serverUrl
        if (serverUrl.isBlank()) {
            return "The address of Music Assistant is not known yet"
        }
        return MusicAssistantClient(serverUrl).playerCommand(token, playerId, command)
    }

    /**
     * Stops the player that the routing pointed at, on a disconnect or a
     * destination switch, so the old player goes quiet instead of playing
     * the stream it no longer owns.
     *
     * @param playerId the player that should stop, or null for none
     */
    fun stopPlaying(playerId: String?) {
        val id = playerId ?: return
        scope.launch {
            withContext(Dispatchers.IO) {
                stopOnServer(id)
            }
        }
    }

    /** Sends one transport command, and swallows the failure into the log. */
    private fun stopOnServer(playerId: String, command: String = "stop") {
        try {
            playerCommand(playerId, command)?.let { reason ->
                Log.i(TAG, "The command for $playerId was refused: $reason")
            }
        } catch (exception: java.io.IOException) {
            Log.i(TAG, "The command for $playerId did not reach the server")
            return
        }
        // The stop quiets the player, and the routing stays: the server
        // still points the player at the phone. The select hands the
        // player back to its own queue, so the phone is out of the path,
        // and the server answers with nothing either way.
        val token = accounts.token ?: return
        val serverUrl = accounts.state.value.serverUrl
        if (serverUrl.isBlank()) return
        try {
            MusicAssistantClient(serverUrl).selectSource(token, playerId, playerId)
        } catch (exception: java.io.IOException) {
            Log.i(TAG, "The deselection of $playerId did not reach the server")
        }
    }

    /** Stops the work that is running. */
    fun close() {
        router?.cancel()
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

        /** How often the app tries to start the picked player. */
        const val ROUTE_ATTEMPTS = 10

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
