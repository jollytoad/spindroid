package com.sendspin.mass

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * A player that the server knows about.
 *
 * The fields are the ones that the picker needs. The server sends more, and
 * the extra fields are ignored.
 *
 * @param playerId the name that the server knows the player by
 * @param name the name of the player
 * @param displayName the name that the server shows for the player
 * @param type the kind of the player
 * @param provider the provider that runs the player
 * @param available the player can be reached right now
 * @param enabled the player is turned on for the user
 * @param state what the player is doing, such as `idle` or `playing`
 * @param playbackState what the player is doing, in the older name
 * @param activeSource the source that the player plays now
 * @param activeGroup the group that the player belongs to
 * @param groupMembers the players that the group holds
 * @param syncedTo the player that the player follows
 * @param hideInUi the server keeps the player out of its own lists
 * @param isPrivate the player is not shared with other accounts
 * @param needsSetup the player still needs to be set up
 */
@JsonClass(generateAdapter = true)
public data class MassPlayer(
    @Json(name = "player_id") val playerId: String,
    val name: String,
    @Json(name = "display_name") val displayName: String? = null,
    val type: String = TYPE_PLAYER,
    val provider: String? = null,
    val available: Boolean = true,
    val enabled: Boolean = true,
    val state: String? = null,
    @Json(name = "playback_state") val playbackState: String? = null,
    @Json(name = "active_source") val activeSource: String? = null,
    @Json(name = "active_group") val activeGroup: String? = null,
    @Json(name = "group_members") val groupMembers: List<String> = emptyList(),
    @Json(name = "synced_to") val syncedTo: String? = null,
    @Json(name = "hide_in_ui") val hideInUi: Boolean = false,
    @Json(name = "private") val isPrivate: Boolean = false,
    @Json(name = "needs_setup") val needsSetup: Boolean = false,
) {
    /** The name to show for the player. */
    public val label: String get() = displayName?.takeIf { it.isNotBlank() } ?: name

    /** Whether the player renders audio of its own. */
    public val playsAudio: Boolean get() = type in PLAYABLE_TYPES

    /** Whether the player is a group of other players. */
    public val isGroup: Boolean get() = type == TYPE_GROUP

    public companion object {
        /** A player with its own support for its kind of device. */
        public const val TYPE_PLAYER: String = "player"

        /** A group of players that play together. */
        public const val TYPE_GROUP: String = "group"

        /** Two speakers that are one device. */
        public const val TYPE_STEREO_PAIR: String = "stereo_pair"

        /** A player that only speaks a protocol, such as AirPlay. */
        public const val TYPE_PROTOCOL: String = "protocol"

        /** A device that only shows what plays, and makes no sound. */
        public const val TYPE_DISPLAY: String = "display"

        /** A device that only shows pictures of the music. */
        public const val TYPE_VISUALIZER: String = "visualizer"

        /** A device that only shows light. */
        public const val TYPE_LIGHT: String = "light"

        /** A device that only takes sound in, such as the phone. */
        public const val TYPE_SOURCE: String = "source"

        /** The kinds of player that make sound. */
        public val PLAYABLE_TYPES: Set<String> = setOf(
            TYPE_PLAYER,
            TYPE_GROUP,
            TYPE_STEREO_PAIR,
            TYPE_PROTOCOL,
        )
    }
}

/** The envelope of the command that lists the players. */
@JsonClass(generateAdapter = true)
internal data class PlayersAllRequest(
    @Json(name = "message_id") val messageId: String,
    val command: String,
    val args: Map<String, String> = emptyMap(),
)

/** The envelope of the command that points a player at a source. */
@JsonClass(generateAdapter = true)
internal data class SelectSourceRequest(
    @Json(name = "message_id") val messageId: String,
    val command: String,
    val args: SelectSourceArgs,
)

/** The arguments of the command that points a player at a source. */
@JsonClass(generateAdapter = true)
internal data class SelectSourceArgs(
    @Json(name = "player_id") val playerId: String,
    val source: String,
)
