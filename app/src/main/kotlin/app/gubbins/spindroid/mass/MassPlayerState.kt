package app.gubbins.spindroid.mass

import app.gubbins.mass.MassPlayer

/**
 * The state of the player picker.
 *
 * The screen shows it, and the manager publishes it.
 *
 * @param players the places the sound can go
 * @param allPlayers every player that the server knows about, so the names
 *   of the members of a group can be found
 * @param loading the list is being read
 * @param choosing the player that is being pointed at the phone right now
 * @param livePlayerId the player that the server last pointed at the phone,
 *   while the routing runs; the row of this player carries the bars
 * @param discarded the players a swipe hid from the list, until the
 *   refresh control runs
 * @param hiddenUnavailable the players the last load skipped, because
 *   they read unavailable; the watch keeps this set, so a player that
 *   turns unavailable after the load keeps its row
 * @param error the reason the last attempt failed
 */
data class MassPlayerState(
    val players: List<MassPlayer> = emptyList(),
    val allPlayers: List<MassPlayer> = emptyList(),
    val loading: Boolean = false,
    val choosing: String? = null,
    val livePlayerId: String? = null,
    val discarded: Set<String> = emptySet(),
    val hiddenUnavailable: Set<String> = emptySet(),
    val error: String? = null,
)
