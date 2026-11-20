package app.gubbins.spindroid.mass

import app.gubbins.mass.MassPlayer

/**
 * The rules that turn the player list of the server into the rows the
 * Destination card shows.
 *
 * The rules are pure, so the unit tests read them on the JVM without a
 * server, the way the session rules of the Source card do.
 */
object MassPlayerRules {

    /**
     * The places the sound can go.
     *
     * The server sends every player it knows about: the ones that make
     * sound, the groups they are in, and the devices that only take
     * sound in, such as the phone itself. The picker shows one row for
     * each place the sound can go, so the members of a group are not
     * rows of their own.
     */
    fun targets(players: List<MassPlayer>): List<MassPlayer> {
        val known = players.map { it.playerId }.toSet()
        return players
            .filter { it.playsAudio }
            .filter { it.enabled && !it.hideInUi && !it.isPrivate && !it.needsSetup }
            .filter { it.activeGroup == null || it.activeGroup !in known }
            .filter { it.syncedTo == null || it.syncedTo !in known }
            .sortedWith(compareByDescending<MassPlayer> { it.available }.thenBy { it.label.lowercase() })
    }

    /**
     * The rows the card shows: the targets, without the ones a swipe
     * hid.
     *
     * A swipe hides a row only until the refresh control runs, which
     * forgets every drop, so the card holds the whole answer of the
     * server again.
     */
    fun visible(players: List<MassPlayer>, discarded: Set<String>): List<MassPlayer> =
        targets(players).filterNot { it.playerId in discarded }
}
