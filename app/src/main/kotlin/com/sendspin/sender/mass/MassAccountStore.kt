package com.sendspin.sender.mass

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * The Music Assistant account the phone signed in to.
 *
 * The token is a secret. It authenticates every command the app sends to
 * Music Assistant, so it lives in encrypted preferences, beside the keys of
 * the protocol.
 */
class MassAccountStore(
    context: Context,
) {
    private val preferences: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        FILE_NAME,
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    /**
     * The token of the signed in account.
     *
     * It is null when no account is signed in.
     */
    var token: String?
        get() = preferences.getString(KEY_TOKEN, null)
        set(value) = preferences.edit { putString(KEY_TOKEN, value) }

    /** The name of the signed in account. */
    var username: String?
        get() = preferences.getString(KEY_USERNAME, null)
        set(value) = preferences.edit { putString(KEY_USERNAME, value) }

    /** The name the operator gave the account. */
    var displayName: String?
        get() = preferences.getString(KEY_DISPLAY_NAME, null)
        set(value) = preferences.edit { putString(KEY_DISPLAY_NAME, value) }

    /**
     * Forgets the account.
     *
     * The token goes, because the account may have been signed out on the
     * server, and a token that no longer works only fails later.
     */
    fun clear() {
        preferences.edit {
            remove(KEY_TOKEN)
            remove(KEY_USERNAME)
            remove(KEY_DISPLAY_NAME)
        }
    }

    private companion object {
        const val FILE_NAME = "sendspin_mass_account"
        const val KEY_TOKEN = "mass_token"
        const val KEY_USERNAME = "mass_username"
        const val KEY_DISPLAY_NAME = "mass_display_name"
    }
}
