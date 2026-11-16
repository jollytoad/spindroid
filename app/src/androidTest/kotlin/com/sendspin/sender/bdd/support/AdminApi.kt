package com.sendspin.sender.bdd.support

import androidx.test.platform.app.InstrumentationRegistry
import com.sendspin.mass.LoginResult
import com.sendspin.mass.MassPlayer
import com.sendspin.mass.MusicAssistantClient
import com.sendspin.mass.PlayersResult
import com.sendspin.protocol.crypto.Base64Url
import com.sendspin.protocol.crypto.X25519
import com.sendspin.sender.storage.EncryptedKeyStore
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * The administrator side of Music Assistant, for the test only.
 *
 * A scenario that starts with a clean pairing has to remove the pairing
 * that an earlier run left on the server. The app cannot do that itself,
 * because it must only pair, not unpair. The test therefore signs in as the
 * administrator and sends the command that the Music Assistant settings
 * screen sends.
 *
 * The calls are plain HTTP, so the test does not depend on the app code that
 * it is about to check.
 */
object AdminApi {
    /** How long one command may take, in milliseconds. */
    const val TIMEOUT_MILLIS = 65_000

    private const val COMMAND_INVOKE_ACTION = "config/players/invoke_action"
    private const val ACTION_UNPAIR = "unpair"

    private var signedInUrl: String? = null
    private var signedInToken: String? = null

    /**
     * Signs in as the administrator and keeps the token.
     *
     * @param serverUrl the address of the Music Assistant
     * @return the token of the administrator
     */
    fun signIn(serverUrl: String): String {
        signedInToken?.let { token ->
            if (signedInUrl == serverUrl) return token
        }
        val user = Credentials.userName
            ?: error("The scenario needs -Pbdd.ma.user, or bdd/credentials.properties.")
        val password = Credentials.password
            ?: error("The scenario needs -Pbdd.ma.password, or bdd/credentials.properties.")
        val result = MusicAssistantClient(serverUrl).login(user, password, "SendSpin BDD")
        val token = when (result) {
            is LoginResult.Success -> result.token
            is LoginResult.Failure ->
                error("The Music Assistant account did not sign in: ${result.reason}")
        }
        signedInUrl = serverUrl
        signedInToken = token
        return token
    }

    /** Forgets the token, so the next scenario signs in again. */
    fun reset() {
        signedInUrl = null
        signedInToken = null
    }

    /**
     * The identifier that the phone uses on the server.
     *
     * The phone names itself with the base64url form of its static public
     * key, so the test can compute the name without asking the screen.
     */
    fun clientId(): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val keys = EncryptedKeyStore(context)
        return Base64Url.encode(X25519.publicKey(keys.staticPrivateKey))
    }

    /**
     * Waits until the server lists the phone.
     *
     * The server lists only the clients that are connected, and the player
     * appears a moment after the session starts.
     *
     * @return the player of the phone
     */
    fun awaitPlayer(serverUrl: String, token: String): MassPlayer {
        val client = MusicAssistantClient(serverUrl)
        val clientId = clientId()
        val deadline = System.currentTimeMillis() + AWAIT_MILLIS
        var lastReason: String? = null
        while (System.currentTimeMillis() < deadline) {
            when (val result = client.listPlayers(token)) {
                is PlayersResult.Success ->
                    result.players.firstOrNull { it.playerId == clientId }?.let { return it }
                is PlayersResult.Failure -> lastReason = result.reason
            }
            Thread.sleep(POLL_MILLIS)
        }
        error(
            "Music Assistant did not list the phone under $clientId within " +
                "${AWAIT_MILLIS / 1000} seconds." + (lastReason?.let { " $it" } ?: ""),
        )
    }

    /**
     * Starts the player that points at the phone.
     *
     * A player that sits idle asks the phone for no audio, so the scenario
     * starts the transport the way a listener would press play on it.
     *
     * @param playerId the identifier that [awaitPlayer] returned
     */
    fun playPlayer(serverUrl: String, token: String, playerId: String) {
        command(
            serverUrl = serverUrl,
            token = token,
            name = "players/cmd/play",
            args = JSONObject()
                .put("player_id", playerId)
                .put("command", "play"),
        )
    }

    /**
     * The raw list of the players, for a diagnosis that a person reads.
     */
    fun dumpPlayers(serverUrl: String, token: String): String {
        val body = JSONObject()
            .put("message_id", UUID.randomUUID().toString())
            .put("command", "players/all")
            .put("args", JSONObject())
        val connection = URL("$serverUrl${MusicAssistantClient.PATH_API}")
            .openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Authorization", "Bearer $token")
        return connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            .let {
                connection.inputStream.bufferedReader().use { reader -> reader.readText() }
            }
    }

    /**
     * Removes the pairing of the phone from the server.
     *
     * The phone must be connected, because the server can only remove a
     * pairing from a client that it can talk to.
     *
     * @param playerId the identifier that [awaitPlayer] returned
     */
    fun unpair(serverUrl: String, token: String, playerId: String) {
        command(
            serverUrl = serverUrl,
            token = token,
            name = COMMAND_INVOKE_ACTION,
            args = JSONObject()
                .put("player_id", playerId)
                .put("action", ACTION_UNPAIR),
        )
    }

    /**
     * Sends one command and returns the raw answer.
     *
     * @throws IllegalStateException the server refused the command
     */
    private fun command(
        serverUrl: String,
        token: String,
        name: String,
        args: JSONObject,
    ): String {
        val body = JSONObject()
            .put("message_id", UUID.randomUUID().toString())
            .put("command", name)
            .put("args", args)
        val connection = URL("$serverUrl${MusicAssistantClient.PATH_API}")
            .openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            check(status in 200..299) {
                "Music Assistant refused $name with the status $status: $text"
            }
            return text
        } finally {
            connection.disconnect()
        }
    }

    /** How long a wait for the phone lasts, in milliseconds. */
    private const val AWAIT_MILLIS = 15_000L

    /** How long a wait sleeps between two searches, in milliseconds. */
    private const val POLL_MILLIS = 500L
}
