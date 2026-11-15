package com.sendspin.sender.mass

/**
 * A sign in that the app just made.
 *
 * The app pairs the phone right after a sign in, and the pairing needs the
 * server and the token of the new account. The event carries them, so the
 * pairing does not have to read the stored account again.
 *
 * @param serverUrl the base address of the server
 * @param token the token of the new account
 * @param administrator the account may change the server, so the phone may
 *   pair on its own
 */
data class MassSignInEvent(
    val serverUrl: String,
    val token: String,
    val administrator: Boolean,
)
