package com.sendspin.mass

import com.squareup.moshi.Moshi
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
            post(body)
        } catch (exception: IOException) {
            return LoginResult.Failure("The server at $baseUrl did not answer")
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
     * Sends a command and reads the answer.
     *
     * @param body the encoded command
     * @return the raw answer
     */
    private fun post(body: ByteArray): String {
        val connection = URL("$baseUrl$PATH_API").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            // The API answers with the result of the command, and the result
            // carries the reason of a failure. So the body of an error is
            // read as well, and the status is only a last resort.
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (text.isEmpty()) {
                throw IOException("The server answered with the status $status")
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

        /** The login provider that takes a name and a password. */
        public const val PROVIDER_BUILTIN: String = "builtin"

        /** How long the app waits for the server, in milliseconds. */
        private const val TIMEOUT_MILLIS = 10_000
    }
}

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
