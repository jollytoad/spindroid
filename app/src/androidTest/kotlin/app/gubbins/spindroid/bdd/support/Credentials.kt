package app.gubbins.spindroid.bdd.support

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume

/**
 * The Music Assistant account that the scenarios sign in with.
 *
 * The account is a secret, so it is not in the repository. The Gradle task
 * reads `bdd/credentials.properties` and passes the values to the device,
 * and `-Pbdd.ma.user` and `-Pbdd.ma.password` override the file.
 *
 * A machine without the file still runs the suite. The scenarios that need
 * the account skip instead, so a developer who only works on the phone does
 * not need the credentials.
 */
object Credentials {
    private val arguments = InstrumentationRegistry.getArguments()

    /** The user name, or null when the machine has none. */
    val userName: String? = arguments.getString("bdd.user")?.takeIf { it.isNotBlank() }

    /** The password, or null when the machine has none. */
    val password: String? = arguments.getString("bdd.password")?.takeIf { it.isNotBlank() }

    /** Whether the machine has an account. */
    val available: Boolean get() = userName != null && password != null

    /**
     * Skips the scenario when the machine has no account.
     *
     * An assumption reports the scenario as skipped, not as failed, so the
     * suite passes on a machine that cannot sign in.
     */
    fun require() {
        Assume.assumeTrue(
            "Set -Pbdd.ma.user and -Pbdd.ma.password, or add bdd/credentials.properties.",
            available,
        )
    }
}
