package app.gubbins.sendspin.session

/**
 * The decision about which connection the client keeps.
 *
 * The specification lets a client hold one connection at a time. When a
 * second server connects, the client keeps the one with the higher rank,
 * and the rank follows the purpose the server declared.
 */
public class AdmissionPolicy(
    /**
     * The server that most recently held the connection while it played
     * audio.
     *
     * The specification uses this server to settle a tie between two
     * idle servers.
     */
    private val lastPlaybackServerId: () -> String?,
) {
    /** The state of the connection the client holds. */
    public data class Holder(
        /** The identifier of the server. */
        val serverId: String,
        /** The purposes the server declared. */
        val activities: Set<Activity>,
        /** The server is running a pairing attempt. */
        val pairingAttempt: Boolean = false,
    )

    /**
     * Decides whether the incoming connection takes over.
     *
     * @param holder the connection the client holds, or null when it holds
     *   none
     * @param incomingServerId the identifier of the incoming server
     * @param incomingActivities the purposes the incoming server declared
     * @param incomingPairingAttempt the incoming server is running a pairing
     *   attempt
     */
    public fun admits(
        holder: Holder?,
        incomingServerId: String,
        incomingActivities: Set<Activity>,
        incomingPairingAttempt: Boolean,
    ): Boolean {
        if (holder == null) return true
        // A pairing attempt is not displaced by an incoming connection.
        if (holder.pairingAttempt) return false
        if (holder.activities.isEmpty() && incomingActivities.isEmpty()) {
            // Two idle servers: only the last one the client played for may
            // take over.
            val last = lastPlaybackServerId()
            return incomingServerId == last && holder.serverId != last
        }
        return rank(incomingActivities) >= rank(holder.activities)
    }
}
