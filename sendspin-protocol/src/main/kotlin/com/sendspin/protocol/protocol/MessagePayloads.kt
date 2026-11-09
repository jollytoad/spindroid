package com.sendspin.protocol.protocol

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * The payload of `client/init`.
 *
 * The client names its static public key and the cipher suite it picked.
 */
@JsonClass(generateAdapter = true)
public data class ClientInitPayload(
    @field:Json(name = "client_id") val clientId: String,
    val version: Int,
    val suite: String,
)

/** The payload of `server/init`. */
@JsonClass(generateAdapter = true)
public data class ServerInitPayload(
    @field:Json(name = "server_id") val serverId: String,
    val version: Int,
)

/** The payload of `noise/handshake`. */
@JsonClass(generateAdapter = true)
public data class NoiseHandshakePayload(
    /** The Noise message, base64url encoded without padding. */
    val data: String,
)

/** The payload of `server/error`. */
@JsonClass(generateAdapter = true)
public data class ServerErrorPayload(
    val reason: String,
)

/** The payload of the first Noise handshake message. */
@JsonClass(generateAdapter = true)
public data class NoiseHandshake1Payload(
    /** The identifier of the pre-shared key the server expects. */
    @field:Json(name = "psk_id") val pskId: String,
    /** The category of the key: `lt`, `pr` or `sn`. */
    @field:Json(name = "psk_category") val pskCategory: String,
)

/** The payload of `server/hello`. */
@JsonClass(generateAdapter = true)
public data class ServerHelloPayload(
    val name: String,
    val languages: List<String>? = null,
    @field:Json(name = "source@v1_support") val sourceSupport: SourceSupport? = null,
)

/** The `source@v1_support` object of `server/hello`. */
@JsonClass(generateAdapter = true)
public data class SourceSupport(
    @field:Json(name = "supported_codecs") val supportedCodecs: List<String>,
)

/** The payload of `client/hello`. */
@JsonClass(generateAdapter = true)
public data class ClientHelloPayload(
    val name: String,
    @field:Json(name = "device_info") val deviceInfo: DeviceInfo? = null,
    @field:Json(name = "supported_roles") val supportedRoles: List<String>,
    @field:Json(name = "player@v1_support") val playerSupport: PlayerSupport? = null,
    @field:Json(name = "supported_pair_methods") val supportedPairMethods: Map<String, Any?> = emptyMap(),
    @field:Json(name = "unpaired_access") val unpairedAccess: UnpairedAccess,
)

/** The `device_info` object of `client/hello`. */
@JsonClass(generateAdapter = true)
public data class DeviceInfo(
    @field:Json(name = "product_name") val productName: String? = null,
    @field:Json(name = "manufacturer") val manufacturer: String? = null,
    @field:Json(name = "software_version") val softwareVersion: String? = null,
    @field:Json(name = "mac_address") val macAddress: String? = null,
)

/** The `player@v1_support` object of `client/hello`. */
@JsonClass(generateAdapter = true)
public data class PlayerSupport(
    @field:Json(name = "supported_formats") val supportedFormats: List<AudioFormat>,
    @field:Json(name = "buffer_capacity") val bufferCapacity: Int,
)

/** One audio format the player supports. */
@JsonClass(generateAdapter = true)
public data class AudioFormat(
    val codec: String,
    val channels: Int,
    @field:Json(name = "sample_rate") val sampleRate: Int,
    @field:Json(name = "bit_depth") val bitDepth: Int,
)

/** The `unpaired_access` object of `client/hello`. */
@JsonClass(generateAdapter = true)
public data class UnpairedAccess(
    val enabled: Boolean,
)

/** The payload of `server/activate`. */
@JsonClass(generateAdapter = true)
public data class ServerActivatePayload(
    val activities: List<String>,
    @field:Json(name = "active_roles") val activeRoles: List<String>? = null,
    val pairing: PairingParameters? = null,
)

/** The `pairing` object of `server/activate`. */
@JsonClass(generateAdapter = true)
public data class PairingParameters(
    val method: String,
    val format: String? = null,
)

/** The payload of `client/time`. */
@JsonClass(generateAdapter = true)
public data class ClientTimePayload(
    @field:Json(name = "client_transmitted") val clientTransmitted: Long,
)

/** The payload of `server/time`. */
@JsonClass(generateAdapter = true)
public data class ServerTimePayload(
    @field:Json(name = "client_transmitted") val clientTransmitted: Long,
    @field:Json(name = "server_received") val serverReceived: Long,
    @field:Json(name = "server_transmitted") val serverTransmitted: Long,
)

/** The payload of `client/state`. */
@JsonClass(generateAdapter = true)
public data class ClientStatePayload(
    val available: Boolean,
    val player: JsonOptional<PlayerState> = JsonOptional.Absent,
    val source: JsonOptional<SourceState> = JsonOptional.Absent,
)

/** The `player` object of `client/state`. */
@JsonClass(generateAdapter = true)
public data class PlayerState(
    val volume: Int? = null,
    val muted: Boolean? = null,
    @field:Json(name = "output_delay_ms") val outputDelayMs: Int,
    @field:Json(name = "required_lead_time_ms") val requiredLeadTimeMs: Int,
    @field:Json(name = "min_buffer_ms") val minBufferMs: Int,
    @field:Json(name = "supported_commands") val supportedCommands: List<String>,
    val format: AudioFormat? = null,
)

/** The `source` object of `client/state`. */
@JsonClass(generateAdapter = true)
public data class SourceState(
    /** `present` or `absent`. */
    val signal: String,
)

/** The payload of `server/state`. */
@JsonClass(generateAdapter = true)
public data class ServerStatePayload(
    val metadata: JsonOptional<MetadataState> = JsonOptional.Absent,
    val controller: JsonOptional<ControllerState> = JsonOptional.Absent,
    val color: JsonOptional<ColorState> = JsonOptional.Absent,
)

/**
 * The `metadata` object of `server/state`.
 *
 * The timestamp is the server clock time in microseconds at which the
 * metadata takes effect.
 */
@JsonClass(generateAdapter = true)
public data class MetadataState(
    val timestamp: Long,
    val title: String? = null,
    val artist: String? = null,
    @field:Json(name = "album_artist") val albumArtist: String? = null,
    val album: String? = null,
    @field:Json(name = "artwork_url") val artworkUrl: String? = null,
    val year: Int? = null,
    val track: Int? = null,
    val progress: MetadataProgress? = null,
)

/** The `progress` object of the `metadata` state. */
@JsonClass(generateAdapter = true)
public data class MetadataProgress(
    /** The playback position in milliseconds, measured at the timestamp. */
    @field:Json(name = "track_progress") val trackProgress: Long,
    /** The track length in milliseconds. Zero means unknown or unlimited. */
    @field:Json(name = "track_duration") val trackDuration: Long,
    /** The playback speed multiplied by 1000. */
    @field:Json(name = "playback_speed") val playbackSpeed: Int,
)

/** The `controller` object of `server/state`. */
@JsonClass(generateAdapter = true)
public data class ControllerState(
    @field:Json(name = "supported_commands") val supportedCommands: List<String>,
    val volume: Int,
    val muted: Boolean,
    /** `off`, `one` or `all`. */
    val repeat: String,
    val shuffle: Boolean,
    @field:Json(name = "seek_max_ms") val seekMaxMs: Long? = null,
)

/** The `color` object of `server/state`. */
@JsonClass(generateAdapter = true)
public data class ColorState(
    val timestamp: Long,
    @field:Json(name = "background_dark") val backgroundDark: List<Int>? = null,
    @field:Json(name = "background_light") val backgroundLight: List<Int>? = null,
    val primary: List<Int>? = null,
    val accent: List<Int>? = null,
    @field:Json(name = "on_dark") val onDark: List<Int>? = null,
    @field:Json(name = "on_light") val onLight: List<Int>? = null,
)

/** The payload of `server/command`. */
@JsonClass(generateAdapter = true)
public data class ServerCommandPayload(
    val player: PlayerCommand? = null,
    val source: SourceCommand? = null,
)

/** The `player` object of `server/command`. */
@JsonClass(generateAdapter = true)
public data class PlayerCommand(
    val command: String,
    val volume: Int? = null,
    val mute: Boolean? = null,
    @field:Json(name = "output_delay_ms") val outputDelayMs: Int? = null,
)

/** The `source` object of `server/command`. */
@JsonClass(generateAdapter = true)
public data class SourceCommand(
    /** `start` or `stop`. */
    val command: String,
)

/** The payload of `client/command`. */
@JsonClass(generateAdapter = true)
public data class ClientCommandPayload(
    val controller: Map<String, Any?>? = null,
)

/** The payload of `client/goodbye`. */
@JsonClass(generateAdapter = true)
public data class ClientGoodbyePayload(
    val reason: String,
)

/** The payload of `client/leave`. It has no fields. */
@JsonClass(generateAdapter = true)
public class ClientLeavePayload

/** The payload of `client-stream/start`. */
@JsonClass(generateAdapter = true)
public data class ClientStreamStartPayload(
    val source: SourceStreamFormat? = null,
    val roles: List<String>? = null,
)

/** The `source` object of `client-stream/start`. */
@JsonClass(generateAdapter = true)
public data class SourceStreamFormat(
    val codec: String,
    val channels: Int,
    @field:Json(name = "sample_rate") val sampleRate: Int,
    @field:Json(name = "bit_depth") val bitDepth: Int,
    @field:Json(name = "codec_header") val codecHeader: String? = null,
)

/** The payload of `client-stream/end`. */
@JsonClass(generateAdapter = true)
public data class ClientStreamEndPayload(
    val roles: List<String>? = null,
)

/** The payload of `stream/start`. */
@JsonClass(generateAdapter = true)
public data class StreamStartPayload(
    @field:Json(name = "server_transmitted") val serverTransmitted: Long,
    val player: PlayerStreamFormat? = null,
)

/** The `player` object of `stream/start`. */
@JsonClass(generateAdapter = true)
public data class PlayerStreamFormat(
    val codec: String,
    @field:Json(name = "sample_rate") val sampleRate: Int,
    val channels: Int,
    @field:Json(name = "bit_depth") val bitDepth: Int,
    @field:Json(name = "codec_header") val codecHeader: String? = null,
)

/** The payload of `stream/end`. */
@JsonClass(generateAdapter = true)
public data class StreamEndPayload(
    val roles: List<String>? = null,
)

/** The payload of `stream/clear`. */
@JsonClass(generateAdapter = true)
public data class StreamClearPayload(
    @field:Json(name = "server_transmitted") val serverTransmitted: Long,
    @field:Json(name = "from_timestamp") val fromTimestamp: Long? = null,
    val roles: List<String>? = null,
)

/** The payload of `group/update`. */
@JsonClass(generateAdapter = true)
public data class GroupUpdatePayload(
    @field:Json(name = "group_id") val groupId: String,
    @field:Json(name = "playback_state") val playbackState: String,
)

/** The payload of `server/unpair`. It has no fields. */
@JsonClass(generateAdapter = true)
public class ServerUnpairPayload

/** The payload of `client/pair-init`. */
@JsonClass(generateAdapter = true)
public data class ClientPairInitPayload(
    @field:Json(name = "pairing_index") val pairingIndex: Int,
    @field:Json(name = "commit_B") val commitB: String? = null,
)

/** The payload of `client/pair-pending`. */
@JsonClass(generateAdapter = true)
public data class ClientPairPendingPayload(
    @field:Json(name = "pairing_index") val pairingIndex: Int,
    val message: String? = null,
)

/** The payload of `client/pair-finalize`. */
@JsonClass(generateAdapter = true)
public data class ClientPairFinalizePayload(
    @field:Json(name = "long_term_psk") val longTermPsk: String? = null,
    @field:Json(name = "wrapped_psk") val wrappedPsk: String? = null,
)

/** The payload of `server/pair-finalize`. It has no fields. */
@JsonClass(generateAdapter = true)
public class ServerPairFinalizePayload

/** The payload of `server/pair-init`. */
@JsonClass(generateAdapter = true)
public data class ServerPairInitPayload(
    @field:Json(name = "nonce_A") val nonceA: String? = null,
)

/** The payload of `server/pair-auth`. */
@JsonClass(generateAdapter = true)
public data class ServerPairAuthPayload(
    @field:Json(name = "pake_msg_1") val pakeMsg1: String,
)

/** The payload of `client/pair-auth`. */
@JsonClass(generateAdapter = true)
public data class ClientPairAuthPayload(
    @field:Json(name = "pake_msg_2") val pakeMsg2: String,
)

/** The payload of `server/pair-confirm`. */
@JsonClass(generateAdapter = true)
public data class ServerPairConfirmPayload(
    @field:Json(name = "server_kc") val serverKc: String,
)

/** The payload of `client/pair-confirm`. */
@JsonClass(generateAdapter = true)
public data class ClientPairConfirmPayload(
    @field:Json(name = "client_kc") val clientKc: String,
    @field:Json(name = "wrapped_nonce_B") val wrappedNonceB: String? = null,
)

/** The payload of `client/pair-retry`. It has no fields. */
@JsonClass(generateAdapter = true)
public class ClientPairRetryPayload

/** The payload of `pair/abort`. */
@JsonClass(generateAdapter = true)
public data class PairAbortPayload(
    val reason: String,
)
