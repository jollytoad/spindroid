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
     * The players a load skips, because they read unavailable.
     *
     * The skip belongs to the load alone: a player that turns
     * unavailable after the load keeps its row, and the row says so,
     * because the watch that follows the load only refreshes what the
     * rows show. The next load reads the list again, and a player that
     * reads available then returns.
     */
    fun unavailableAtLoad(players: List<MassPlayer>): Set<String> =
        players.filterNot { it.available }.mapTo(mutableSetOf()) { it.playerId }

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
     * hid and the ones the last load skipped.
     *
     * A swipe hides a row only until the show-all control runs, which
     * forgets every drop, so the card holds the whole answer of the
     * server again. An unavailable player reads the same way: the load
     * skips it, and the next load reads the list again.
     */
    fun visible(
        players: List<MassPlayer>,
        discarded: Set<String>,
        hiddenUnavailable: Set<String> = emptySet(),
    ): List<MassPlayer> =
        targets(players).filterNot { it.playerId in discarded || it.playerId in hiddenUnavailable }
}
