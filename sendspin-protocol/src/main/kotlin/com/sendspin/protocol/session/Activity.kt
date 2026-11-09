package com.sendspin.protocol.session

/**
 * The purpose a server declares for a connection.
 *
 * The specification ranks the purposes, and the client keeps the
 * connection with the highest one.
 */
public enum class Activity {
    /** The server wants to play audio on the client. */
    PLAYBACK,

    /** The server wants to pair with the client. */
    PAIRING,
    ;

    public companion object {
        /**
         * The activity with the given wire name.
         *
         * @param name the name as it appears in `server/activate`
         * @return the activity, or null when the name is not one of the two
         */
        public fun fromWireName(name: String): Activity? =
            entries.firstOrNull { it.name.lowercase() == name }
    }
}

/**
 * The rank of a set of activities.
 *
 * The specification ranks the purposes from highest to lowest: `playback`,
 * then `pairing`, then a connection with no purpose at all. A client holds
 * the connection with the highest rank.
 */
public fun rank(activities: Set<Activity>): Int = when {
    activities.contains(Activity.PLAYBACK) -> RANK_PLAYBACK
    activities.contains(Activity.PAIRING) -> RANK_PAIRING
    else -> RANK_NONE
}

/** The rank of a connection that plays audio. */
public const val RANK_PLAYBACK: Int = 2

/** The rank of a connection that pairs. */
public const val RANK_PAIRING: Int = 1

/** The rank of a connection with no purpose. */
public const val RANK_NONE: Int = 0
