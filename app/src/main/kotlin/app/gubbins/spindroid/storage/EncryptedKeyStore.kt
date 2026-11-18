package app.gubbins.spindroid.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import app.gubbins.sendspin.crypto.Base64Url
import app.gubbins.sendspin.crypto.X25519
import app.gubbins.sendspin.psk.ClientKeyStore
import app.gubbins.sendspin.psk.PskRecord
import app.gubbins.sendspin.psk.PskRecordCodec
import java.security.SecureRandom

/**
 * The keys the client keeps on the device.
 *
 * The keys live in encrypted preferences, because the pairing PSK and the
 * identity key decide who may use the phone's audio. The private key never
 * leaves the device.
 */
class EncryptedKeyStore(
    context: Context,
) : ClientKeyStore {
    private val preferences: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        FILE_NAME,
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private val random = SecureRandom()

    /**
     * The client's static private key.
     *
     * The key is drawn on the first run and never changes, because the
     * public key is the client's identifier. A server recognises the same
     * phone across reconnections because of it.
     */
    override val staticPrivateKey: ByteArray by lazy {
        val existing = preferences.getString(KEY_PRIVATE, null)
        if (existing != null) {
            Base64Url.decode(existing)?.let { return@lazy it }
        }
        val key = X25519.generatePrivateKey(random)
        preferences.edit().putString(KEY_PRIVATE, Base64Url.encode(key)).apply()
        key
    }

    /**
     * The pairing PSK of this client.
     *
     * The key never changes, so the pairing token never changes either. The
     * operator can paste the same token into any number of servers.
     */
    override val pairingPsk: ByteArray by lazy {
        val existing = preferences.getString(KEY_PAIRING_PSK, null)
        if (existing != null) {
            Base64Url.decode(existing)?.let { return@lazy it }
        }
        val psk = ByteArray(32).also { random.nextBytes(it) }
        preferences.edit().putString(KEY_PAIRING_PSK, Base64Url.encode(psk)).apply()
        psk
    }

    override var lastPlaybackServerId: String?
        get() = preferences.getString(KEY_LAST_PLAYBACK_SERVER, null)
        set(value) {
            preferences.edit().putString(KEY_LAST_PLAYBACK_SERVER, value).apply()
        }

    override fun longTermRecords(): List<PskRecord> = records()

    override fun findLongTerm(pskId: String): PskRecord? =
        records().firstOrNull { it.pskId == pskId }

    override fun putLongTerm(record: PskRecord) {
        val all = records().filterNot { it.serverId == record.serverId }.toMutableList()
        all.add(0, record)
        // The specification wants at least five records. A new pairing
        // never fails for want of storage, so the oldest one goes.
        while (all.size > CAPACITY) {
            all.removeAt(all.size - 1)
        }
        write(all)
    }

    override fun removeLongTerm(serverId: String) {
        write(records().filterNot { it.serverId == serverId })
    }

    /** The records, most recently used first. */
    private fun records(): List<PskRecord> =
        PskRecordCodec.decode(preferences.getString(KEY_RECORDS, null).orEmpty())

    /** Writes the records, most recently used first. */
    private fun write(records: List<PskRecord>) {
        preferences.edit().putString(KEY_RECORDS, PskRecordCodec.encode(records)).apply()
    }

    private companion object {
        const val FILE_NAME = "sendspin_keys"
        const val KEY_PRIVATE = "identity_private_key"
        const val KEY_PAIRING_PSK = "pairing_psk"
        const val KEY_RECORDS = "pairing_records"
        const val KEY_LAST_PLAYBACK_SERVER = "last_playback_server"

        /** The number of records the store keeps. The specification wants at least five. */
        const val CAPACITY = 5
    }
}
