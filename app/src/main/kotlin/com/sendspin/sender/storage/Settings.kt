package com.sendspin.sender.storage

import android.content.Context
import androidx.core.content.edit
import com.sendspin.protocol.crypto.CipherSuiteBenchmark
import com.sendspin.protocol.crypto.NoiseSuite

/**
 * The settings the user chooses.
 *
 * The cipher suite lives here. It is not secret, so it sits in plain
 * preferences.
 *
 * The phone does not dial the server, so there is no server address to
 * keep. The server finds the phone over the advertisement on the local
 * network.
 */
class Settings(context: Context) {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

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

    private companion object {
        const val FILE_NAME = "sendspin_settings"
        const val KEY_SUITE = "cipher_suite"
    }
}
