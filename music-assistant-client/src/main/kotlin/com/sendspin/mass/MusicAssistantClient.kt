package com.sendspin.mass

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * The client for the Music Assistant API.
 *
 * The API takes one envelope for every command. The name of the command
 * goes in `command`, the arguments go in `args`, and the answer is the
 * result of the command without an envelope of its own.
 *
 * The class is plain Kotlin and plain Java, so the same code runs on the
 * phone and in the tests.
 */
public class MusicAssistantClient(
    /** The base address of the server, such as `http://192.168.8.242:8095`. */
    private val baseUrl: String,
) {
    private val moshi = Moshi.Builder().build()
    private val requestAdapter = moshi.adapter(LoginRequest::class.java)
    private val responseAdapter = moshi.adapter(LoginResponse::class.java)
    private val playersAdapter = moshi.adapter<List<MassPlayer>>(
        Types.newParameterizedType(List::class.java, MassPlayer::class.java),
    )
    private val playersAllAdapter = moshi.adapter(PlayersAllRequest::class.java)
    private val selectSourceAdapter = moshi.adapter(SelectSourceRequest::class.java)

    /**
     * Signs in to the server.
     *
     * @param username the account name
     * @param password the password of the account
     * @param deviceName the name that the token carries, so the operator can
     *   tell the phone apart from the other clients in the server's list of
     *   tokens
     * @return the token and the account, or the reason the server gave
     */
    public fun login(username: String, password: String, deviceName: String): LoginResult {
        val request = LoginRequest(
            messageId = UUID.randomUUID().toString(),
            command = COMMAND_LOGIN,
            args = LoginArgs(
                username = username,
                password = password,
                providerId = PROVIDER_BUILTIN,
                deviceName = deviceName,
            ),
        )
        val body = requestAdapter.toJson(request).toByteArray(Charsets.UTF_8)
        val answer = try {
            post(body, token = null)
        } catch (exception: ApiException) {
            // The reason matters. A phone that cannot reach the server and a
            // phone that was refused by it need a different answer.
            return LoginResult.Failure("The server at $baseUrl did not answer: ${reasonOf(exception)}")
        } catch (exception: IOException) {
            return LoginResult.Failure("The server at $baseUrl did not answer: ${exception.message}")
        }
        if (answer.isBlank()) {
            return LoginResult.Failure("The server sent an empty answer")
        }
        val response = try {
            responseAdapter.fromJson(answer)
        } catch (exception: Exception) {
            return LoginResult.Failure("The server sent an answer the app could not read")
        } ?: return LoginResult.Failure("The server sent an empty answer")

        if (!response.success) {
            return LoginResult.Failure(response.error ?: "The server refused the sign in")
        }
        val token = response.accessToken
            ?: return LoginResult.Failure("The server sent no token")
        return LoginResult.Success(
            token = token,
            username = response.user?.username ?: username,
            displayName = response.user?.displayName,
        )
    }

    /**
     * Lists the players that the server knows about.
     *
     * The list holds the players that make sound, and also the devices that
     * only take sound in. The caller decides which of them to show.
     *
     * @param token the token of the signed in account
     * @return the players, or the reason the server gave
     */
    public fun listPlayers(token: String): PlayersResult {
        val request = PlayersAllRequest(
            messageId = UUID.randomUUID().toString(),
            command = COMMAND_PLAYERS_ALL,
        )
        val body = playersAllAdapter.toJson(request).toByteArray(Charsets.UTF_8)
        val answer = try {
            post(body, token)
        } catch (exception: ApiException) {
            return PlayersResult.Failure("The server did not answer: ${exception.reason}")
        } catch (exception: IOException) {
            return PlayersResult.Failure("The server did not answer: ${exception.message}")
        }
        val players = try {
            playersAdapter.fromJson(answer)
        } catch (exception: Exception) {
            return PlayersResult.Failure("The server sent an answer the app could not read")
        } ?: return PlayersResult.Failure("The server sent no players")
        return PlayersResult.Success(players)
    }

    /**
     * Points a player at a source.
     *
     * The server answers with nothing when the command did what it was
     * asked. It also answers with nothing when it did not, so the caller
     * reads the list of players again to see what really happened.
     *
     * @param token the token of the signed in account
     * @param playerId the player that should play the source
     * @param sourceUri the source that the player should play
     * @return nothing, or the reason the server gave
     */
    public fun selectSource(token: String, playerId: String, sourceUri: String): SelectSourceResult {
        val request = SelectSourceRequest(
            messageId = UUID.randomUUID().toString(),
            command = COMMAND_SELECT_SOURCE,
            args = SelectSourceArgs(playerId = playerId, source = sourceUri),
        )
        val body = selectSourceAdapter.toJson(request).toByteArray(Charsets.UTF_8)
        try {
            post(body, token)
        } catch (exception: ApiException) {
            return SelectSourceResult.Failure("The server did not answer: ${exception.reason}")
        } catch (exception: IOException) {
            return SelectSourceResult.Failure("The server did not answer: ${exception.message}")
        }
        return SelectSourceResult.Success
    }

    /**
     * The reason that the server gave.
     *
     * The server answers a refusal with plain text, but the login command
     * answers with a body that carries the reason in the `error` field.
     * The reason in the server's own words is better than the status.
     */
    private fun reasonOf(exception: ApiException): String {
        val response = try {
            responseAdapter.fromJson(exception.body)
        } catch (ignored: Exception) {
            null
        }
        return response?.error?.takeIf { it.isNotBlank() } ?: exception.reason
    }

    /**
     * Sends a command and reads the answer.
     *
     * @param body the encoded command
     * @param token the token to authenticate with, or nothing for the
     *   commands that need no account
     * @return the raw answer
     * @throws IOException the server refused the command, and said why
     */
    private fun post(body: ByteArray, token: String?): String {
        val connection = URL("$baseUrl$PATH_API").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            if (token != null) {
                connection.setRequestProperty("Authorization", "Bearer $token")
            }
            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            // The API answers with the result of the command, and the result
            // carries the reason of a failure. So the body of an error is
            // read as well, and the status is only a last resort.
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                // The reason of a refusal is plain text, and it says more than
                // the status does.
                throw ApiException(
                    reason = text.ifBlank { "the server answered with the status $status" },
                    body = text,
                )
            }
            return text
        } finally {
            connection.disconnect()
        }
    }

    public companion object {
        /** The path of the command endpoint. */
        public const val PATH_API: String = "/api"

        /** The name of the login command. */
        public const val COMMAND_LOGIN: String = "auth/login"

        /** The name of the command that lists the players. */
        public const val COMMAND_PLAYERS_ALL: String = "players/all"

        /** The name of the command that points a player at a source. */
        public const val COMMAND_SELECT_SOURCE: String = "players/cmd/select_source"

        /** The login provider that takes a name and a password. */
        public const val PROVIDER_BUILTIN: String = "builtin"

        /** How long the app waits for the server, in milliseconds. */
        private const val TIMEOUT_MILLIS = 10_000
    }
}

/**
 * The server refused a command.
 *
 * @param reason the reason the server gave, in its own words
 * @param body the whole answer of the server
 */
private class ApiException(
    val reason: String,
    val body: String,
) : IOException(reason)

/** The outcome of a sign in. */
public sealed interface LoginResult {
    /**
     * The server gave a token.
     *
     * @param token the token that authenticates the later commands
     * @param username the name of the account
     * @param displayName the name the operator gave the account
     */
    public data class Success(
        val token: String,
        val username: String,
        val displayName: String?,
    ) : LoginResult

    /**
     * The server refused the sign in.
     *
     * @param reason the reason the server gave, in its own words
     */
    public data class Failure(val reason: String) : LoginResult
}

/** The outcome of a request for the list of players. */
public sealed interface PlayersResult {
    /**
     * The server gave the list.
     *
     * @param players every player that the server knows about
     */
    public data class Success(val players: List<MassPlayer>) : PlayersResult

    /**
     * The server did not give the list.
     *
     * @param reason the reason the server gave, in its own words
     */
    public data class Failure(val reason: String) : PlayersResult
}

/** The outcome of a request to point a player at a source. */
public sealed interface SelectSourceResult {
    /** The server took the command. */
    public data object Success : SelectSourceResult

    /**
     * The server did not take the command.
     *
     * @param reason the reason the server gave, in its own words
     */
    public data class Failure(val reason: String) : SelectSourceResult
}
