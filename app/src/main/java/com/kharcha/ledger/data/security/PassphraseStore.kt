package com.kharcha.ledger.data.security

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the database passphrase.
 *
 * The passphrase itself is random per install and never leaves the device; it is
 * kept in EncryptedSharedPreferences, whose own key lives in the Android
 * Keystore and is therefore backed by hardware on most phones. Losing the phone
 * loses the ledger, which is the correct trade for an app with no server.
 */
@Singleton
class PassphraseStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /** Returns the existing passphrase, creating one on first launch. */
    fun passphrase(): ByteArray {
        prefs.getString(KEY, null)?.let { return Base64.decode(it, Base64.NO_WRAP) }
        val generated = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString(KEY, Base64.encodeToString(generated, Base64.NO_WRAP)).apply()
        return generated
    }

    private companion object {
        const val FILE_NAME = "kharcha-keys"
        const val KEY = "db-passphrase"
    }
}
