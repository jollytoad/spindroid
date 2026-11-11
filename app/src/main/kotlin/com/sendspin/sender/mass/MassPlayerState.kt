package com.sendspin.sender.mass

import com.sendspin.mass.MassPlayer

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
 * @param error the reason the last attempt failed
 */
data class MassPlayerState(
    val players: List<MassPlayer> = emptyList(),
    val allPlayers: List<MassPlayer> = emptyList(),
    val loading: Boolean = false,
    val choosing: String? = null,
    val error: String? = null,
)
