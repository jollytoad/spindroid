package app.gubbins.mass

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * The payloads of the Music Assistant API.
 *
 * The API takes one envelope for every command, and the body of the command
 * goes in the `args` object. The answer is the plain result of the command,
 * without an envelope of its own.
 */

/** The envelope of a command. */
@JsonClass(generateAdapter = true)
internal data class LoginRequest(
    @Json(name = "message_id") val messageId: String,
    val command: String,
    val args: LoginArgs,
)

/** The arguments of the login command. */
@JsonClass(generateAdapter = true)
internal data class LoginArgs(
    val username: String,
    val password: String,
    @Json(name = "provider_id") val providerId: String,
    @Json(name = "device_name") val deviceName: String,
)

/** The answer of the login command. */
@JsonClass(generateAdapter = true)
internal data class LoginResponse(
    val success: Boolean = false,
    @Json(name = "access_token") val accessToken: String? = null,
    val error: String? = null,
    val user: LoginUser? = null,
)

/** The account that logged in. */
@JsonClass(generateAdapter = true)
internal data class LoginUser(
    val username: String? = null,
    @Json(name = "display_name") val displayName: String? = null,
    val role: String? = null,
)
