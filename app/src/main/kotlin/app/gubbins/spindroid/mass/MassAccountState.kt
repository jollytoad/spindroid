package app.gubbins.spindroid.mass

/**
 * The state of the Music Assistant account.
 *
 * The screen shows it, and the manager publishes it.
 *
 * @param serverUrl the base address of the server
 * @param username the name of the signed in account
 * @param displayName the name the operator gave the account
 * @param administrator the account may change the server, so the phone may
 *   pair on its own
 * @param signingIn the sign in is running
 * @param error the reason the last sign in failed
 */
data class MassAccountState(
    val serverUrl: String = "",
    val username: String? = null,
    val displayName: String? = null,
    val administrator: Boolean = false,
    val signingIn: Boolean = false,
    val error: String? = null,
) {
    /** Whether an account is signed in. */
    val signedIn: Boolean get() = username != null
}
