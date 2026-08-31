package com.kharcha.ledger.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "kharcha-settings")

data class AppSettings(
    val onboardingComplete: Boolean = false,
    /** Off by default: the app keeps what it extracted, not the words. */
    val keepRawMessages: Boolean = false,
    val biometricLock: Boolean = false,
    val autoMergeDuplicates: Boolean = true,
    val lastImportAt: Instant? = null,
    val smallSpendThresholdPaise: Long = 20_000
)

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            onboardingComplete = prefs[ONBOARDING] ?: false,
            keepRawMessages = prefs[KEEP_RAW] ?: false,
            biometricLock = prefs[BIOMETRIC] ?: false,
            autoMergeDuplicates = prefs[AUTO_MERGE] ?: true,
            lastImportAt = prefs[LAST_IMPORT]?.let { Instant.ofEpochMilli(it) },
            smallSpendThresholdPaise = prefs[SMALL_SPEND] ?: 20_000
        )
    }

    suspend fun setOnboardingComplete(complete: Boolean) = put(ONBOARDING, complete)

    suspend fun setKeepRawMessages(keep: Boolean) = put(KEEP_RAW, keep)

    suspend fun setBiometricLock(enabled: Boolean) = put(BIOMETRIC, enabled)

    suspend fun setAutoMergeDuplicates(enabled: Boolean) = put(AUTO_MERGE, enabled)

    suspend fun setSmallSpendThreshold(paise: Long) = put(SMALL_SPEND, paise)

    suspend fun setLastImportAt(instant: Instant) = put(LAST_IMPORT, instant.toEpochMilli())

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }

    private companion object {
        val ONBOARDING = booleanPreferencesKey("onboarding_complete")
        val KEEP_RAW = booleanPreferencesKey("keep_raw_messages")
        val BIOMETRIC = booleanPreferencesKey("biometric_lock")
        val AUTO_MERGE = booleanPreferencesKey("auto_merge_duplicates")
        val LAST_IMPORT = longPreferencesKey("last_import_at")
        val SMALL_SPEND = longPreferencesKey("small_spend_threshold")
    }
}
