package com.sendspin.sender.storage

import android.content.Context
import androidx.core.content.edit
import com.sendspin.protocol.crypto.CipherSuiteBenchmark
import com.sendspin.protocol.crypto.NoiseSuite

/**
 * The settings the user chooses.
 *
 * The server address, the capture source, and the cipher suite live here.
 * They are not secret, so they sit in plain preferences.
 */
class Settings(context: Context) {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** The address of the Music Assistant server. */
    var serverAddress: String
        get() = preferences.getString(KEY_SERVER, DEFAULT_SERVER) ?: DEFAULT_SERVER
        set(value) = preferences.edit { putString(KEY_SERVER, value) }

    /** Where the audio comes from. */
    var captureSource: CaptureSource
        get() = CaptureSource.fromName(
            preferences.getString(KEY_CAPTURE_SOURCE, CaptureSource.SYSTEM_AUDIO.name),
        )
        set(value) = preferences.edit { putString(KEY_CAPTURE_SOURCE, value.name) }

    /**
     * The cipher suite the client picks.
     *
     * The suite is measured on the device, because a device without AES
     * hardware runs the software cipher too slowly for a stream of audio.
     * The user may pick one, and then the app keeps that choice.
     */
    var cipherSuite: NoiseSuite
        get() = NoiseSuite.fromWireName(
            preferences.getString(KEY_SUITE, "") ?: "",
        ) ?: CipherSuiteBenchmark.preferredSuite()
        set(value) = preferences.edit { putString(KEY_SUITE, value.protocolName) }

    /**
     * Which way the connection goes.
     *
     * The specification forbids doing both at once. When the app dials the
     * server, it must not advertise itself, and the other way round.
     */
    var direction: ConnectionDirection
        get() = ConnectionDirection.fromName(
            preferences.getString(KEY_DIRECTION, ConnectionDirection.DIAL_OUT.name),
        )
        set(value) = preferences.edit { putString(KEY_DIRECTION, value.name) }

    private companion object {
        const val FILE_NAME = "sendspin_settings"
        const val KEY_SERVER = "server_address"
        const val KEY_CAPTURE_SOURCE = "capture_source"
        const val KEY_SUITE = "cipher_suite"
        const val KEY_DIRECTION = "connection_direction"

        /** The address the app starts with. */
        const val DEFAULT_SERVER = "192.168.1.10"
    }
}

/**
 * Which way the connection goes.
 */
enum class ConnectionDirection {
    /** The app dials the server. */
    DIAL_OUT,

    /** The app waits for the server to dial it. */
    ACCEPT_INCOMING,
    ;

    companion object {
        /** The direction with the given name, or dialing out. */
        fun fromName(name: String?): ConnectionDirection =
            entries.firstOrNull { it.name == name } ?: DIAL_OUT
    }
}

/** Where the app takes the audio from. */
enum class CaptureSource {
    /** The system audio, through a media projection. */
    SYSTEM_AUDIO,

    /** The microphone. */
    MICROPHONE,
    ;

    companion object {
        /** The source with the given name, or the system audio. */
        fun fromName(name: String?): CaptureSource =
            entries.firstOrNull { it.name == name } ?: SYSTEM_AUDIO
    }
}
