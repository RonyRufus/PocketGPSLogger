package com.pocketgps.logger.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "pocket_gps_settings")

/**
 * Persists [AppSettings] via Preferences DataStore. Exposes a single cold [settings] Flow that
 * the service and UI both observe.
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val TRIGGER = stringPreferencesKey("trigger_phrase")
        val CONFIRM = stringPreferencesKey("confirmation_mode")
        val GPS_TIMEOUT = intPreferencesKey("gps_timeout_seconds")
        val CACHE_POLICY = stringPreferencesKey("cached_policy")
        val CACHE_MAX_AGE = intPreferencesKey("cached_max_age_seconds")
        val MAX_ACCURACY = floatPreferencesKey("max_accuracy_meters")
        val COOLDOWN = intPreferencesKey("cooldown_seconds")
        val GPS_PRE_WARM = booleanPreferencesKey("gps_pre_warm")
        val HAPTIC_FEEDBACK = booleanPreferencesKey("haptic_feedback")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        val defaults = AppSettings()
        AppSettings(
            triggerPhrase = prefs[Keys.TRIGGER]?.takeIf { it.isNotBlank() } ?: defaults.triggerPhrase,
            confirmationMode = prefs[Keys.CONFIRM]
                ?.let { runCatching { ConfirmationMode.valueOf(it) }.getOrNull() }
                ?: defaults.confirmationMode,
            gpsTimeoutSeconds = prefs[Keys.GPS_TIMEOUT]
                ?.coerceIn(AppSettings.MIN_GPS_TIMEOUT_SECONDS, AppSettings.MAX_GPS_TIMEOUT_SECONDS)
                ?: defaults.gpsTimeoutSeconds,
            cachedLocationPolicy = prefs[Keys.CACHE_POLICY]
                ?.let { runCatching { CachedLocationPolicy.valueOf(it) }.getOrNull() }
                ?: defaults.cachedLocationPolicy,
            cachedMaxAgeSeconds = prefs[Keys.CACHE_MAX_AGE] ?: defaults.cachedMaxAgeSeconds,
            maxAccuracyMeters = prefs[Keys.MAX_ACCURACY] ?: defaults.maxAccuracyMeters,
            cooldownSeconds = prefs[Keys.COOLDOWN]
                ?.coerceIn(AppSettings.MIN_COOLDOWN_SECONDS, AppSettings.MAX_COOLDOWN_SECONDS)
                ?: defaults.cooldownSeconds,
            gpsPreWarm = prefs[Keys.GPS_PRE_WARM] ?: defaults.gpsPreWarm,
            hapticFeedback = prefs[Keys.HAPTIC_FEEDBACK] ?: defaults.hapticFeedback,
        )
    }

    suspend fun setTriggerPhrase(phrase: String) = context.dataStore.edit {
        it[Keys.TRIGGER] = phrase.trim().lowercase()
    }

    suspend fun setConfirmationMode(mode: ConfirmationMode) = context.dataStore.edit {
        it[Keys.CONFIRM] = mode.name
    }

    suspend fun setGpsTimeoutSeconds(value: Int) = context.dataStore.edit {
        it[Keys.GPS_TIMEOUT] = value.coerceIn(
            AppSettings.MIN_GPS_TIMEOUT_SECONDS, AppSettings.MAX_GPS_TIMEOUT_SECONDS,
        )
    }

    suspend fun setCachedLocationPolicy(policy: CachedLocationPolicy) = context.dataStore.edit {
        it[Keys.CACHE_POLICY] = policy.name
    }

    suspend fun setCachedMaxAgeSeconds(value: Int) = context.dataStore.edit {
        it[Keys.CACHE_MAX_AGE] = value.coerceAtLeast(1)
    }

    suspend fun setMaxAccuracyMeters(value: Float) = context.dataStore.edit {
        it[Keys.MAX_ACCURACY] = value.coerceAtLeast(1f)
    }

    suspend fun setCooldownSeconds(value: Int) = context.dataStore.edit {
        it[Keys.COOLDOWN] = value.coerceIn(
            AppSettings.MIN_COOLDOWN_SECONDS, AppSettings.MAX_COOLDOWN_SECONDS,
        )
    }

    suspend fun setGpsPreWarm(value: Boolean) = context.dataStore.edit {
        it[Keys.GPS_PRE_WARM] = value
    }

    suspend fun setHapticFeedback(value: Boolean) = context.dataStore.edit {
        it[Keys.HAPTIC_FEEDBACK] = value
    }
}
