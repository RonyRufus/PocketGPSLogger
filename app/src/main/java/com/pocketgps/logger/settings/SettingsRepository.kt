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
import com.pocketgps.logger.audio.TriggerMatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "pocket_gps_settings")

/**
 * Persists [AppSettings] via Preferences DataStore. Exposes a single cold [settings] Flow that
 * the service and UI both observe.
 *
 * Also persists the observation draft (the text currently typed on the capture card) so an
 * interrupted note survives app restarts, keyed separately from settings semantics.
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        /** Legacy single-phrase key (v1 settings). Read for migration; no longer written. */
        val TRIGGER_LEGACY = stringPreferencesKey("trigger_phrase")

        /** Newline-joined trigger phrases. */
        val TRIGGERS = stringPreferencesKey("trigger_phrases")
        val CONFIRM = stringPreferencesKey("confirmation_mode")
        val GPS_TIMEOUT = intPreferencesKey("gps_timeout_seconds")
        val CACHE_POLICY = stringPreferencesKey("cached_policy")
        val CACHE_MAX_AGE = intPreferencesKey("cached_max_age_seconds")
        val MAX_ACCURACY = floatPreferencesKey("max_accuracy_meters")
        val COOLDOWN = intPreferencesKey("cooldown_seconds")
        val GPS_PRE_WARM = booleanPreferencesKey("gps_pre_warm")
        val HAPTIC_FEEDBACK = booleanPreferencesKey("haptic_feedback")
        val VOICE_NOTES = booleanPreferencesKey("voice_notes")
        val DRAFT_OBSERVATION = stringPreferencesKey("draft_observation")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        val defaults = AppSettings()
        AppSettings(
            triggerPhrases = readTriggers(prefs, defaults),
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
            voiceNotes = prefs[Keys.VOICE_NOTES] ?: defaults.voiceNotes,
        )
    }

    /** The observation draft shown on the capture card; empty string when none. */
    val draftObservation: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.DRAFT_OBSERVATION] ?: ""
    }

    /**
     * Migrate the legacy single trigger to the list form, then keep only valid phrases.
     * Falls back to the default when nothing valid is stored.
     */
    private fun readTriggers(prefs: Preferences, defaults: AppSettings): List<String> {
        val stored = prefs[Keys.TRIGGERS]
            ?.split('\n')
            ?.map { it.trim().lowercase() }
            ?.filter { TriggerMatcher.isValidTrigger(it) }
            ?.distinct()
            ?.takeIf { it.isNotEmpty() }
        if (stored != null) return stored
        val legacy = prefs[Keys.TRIGGER_LEGACY]?.trim()?.lowercase()
        if (legacy != null && TriggerMatcher.isValidTrigger(legacy)) return listOf(legacy)
        return defaults.triggerPhrases
    }

    /** Backwards-compatible setter; accepts one phrase. */
    suspend fun setTriggerPhrase(phrase: String) = setTriggerPhrases(listOf(phrase))

    suspend fun setTriggerPhrases(phrases: List<String>) = context.dataStore.edit {
        val valid = phrases.map { p -> p.trim().lowercase() }
            .filter { p -> TriggerMatcher.isValidTrigger(p) }
            .distinct()
        it[Keys.TRIGGERS] =
            if (valid.isEmpty()) AppSettings.DEFAULT_TRIGGER_PHRASE else valid.joinToString("\n")
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

    suspend fun setVoiceNotes(value: Boolean) = context.dataStore.edit {
        it[Keys.VOICE_NOTES] = value
    }

    suspend fun setDraftObservation(text: String) = context.dataStore.edit {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) it.remove(Keys.DRAFT_OBSERVATION)
        else it[Keys.DRAFT_OBSERVATION] = trimmed
    }

    suspend fun clearDraftObservation() = context.dataStore.edit {
        it.remove(Keys.DRAFT_OBSERVATION)
    }
}
