package com.pocketgps.logger.engine

import com.pocketgps.logger.data.LocationRecord
import com.pocketgps.logger.feedback.HapticCue
import com.pocketgps.logger.location.LocationAcquirer
import com.pocketgps.logger.location.LocationRepository
import com.pocketgps.logger.location.LocationSource
import com.pocketgps.logger.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The single save pipeline used by every input path — pocket double-tap, notification button,
 * in-app button. One acquire → insert → confirm contract, one warning builder, one haptic
 * vocabulary, so all triggers behave identically.
 *
 * Writes are verified: a save only reports success after Room confirms the insert. Haptics are
 * the primary confirmation channel (works locked, pocketed, screen-off).
 */
class SavePipeline(
    private val repository: LocationRepository,
    /** Exposed so the service can drive GPS pre-warm through the same acquirer instance. */
    val acquirer: LocationAcquirer,
    private val haptics: HapticCue,
) {

    sealed interface Result {
        /** Record committed to storage. [warning] is set when the fix was stale or inaccurate. */
        data class Saved(val record: LocationRecord, val warning: String? = null) : Result

        /** No usable location (GPS off, timeout without fallback). */
        data class Unavailable(val reason: String) : Result

        /** The database write did not commit. */
        data class WriteFailed(val reason: String) : Result
    }

    /** Id of the most recent successfully inserted row, for [undoLast]. */
    @Volatile
    var lastSavedId: Long = 0L
        private set

    @Volatile
    var lastSavedAtMs: Long = 0L
        private set

    /**
     * Save one spot. [triggerLabel] records *what* caused the save ("double tap",
     * "notification", "button") so exports can show the provenance of every row.
     */
    suspend fun save(triggerLabel: String, settings: AppSettings): Result {
        val triggerAt = System.currentTimeMillis()
        return try {
            when (val acquisition = acquirer.acquire(triggerAt, settings)) {
                is LocationAcquirer.Result.Success -> persist(
                    acquisition.fix, triggerLabel, triggerAt, settings,
                )

                is LocationAcquirer.Result.Unavailable -> {
                    haptics.failure()
                    Result.Unavailable(acquisition.reason)
                }
            }
        } catch (t: Throwable) {
            haptics.failure()
            Result.WriteFailed(t.message ?: "Save failed")
        }
    }

    private suspend fun persist(
        fix: LocationAcquirer.Fix,
        triggerLabel: String,
        triggerAt: Long,
        settings: AppSettings,
    ): Result {
        val record = LocationRecord(
            latitude = fix.latitude,
            longitude = fix.longitude,
            triggerTimestamp = triggerAt,
            locationTimestamp = fix.fixTimestampMs,
            accuracyMeters = fix.accuracyMeters,
            locationSource = fix.source.wire,
            locationAgeSeconds = fix.ageSeconds,
            triggerPhrase = triggerLabel,
            saveStatus = "success",
            notes = buildWarning(fix, settings),
        )
        val id = withContext(Dispatchers.IO) {
            runCatching { repository.insert(record) }.getOrNull()
        }
        return if (id == null) {
            haptics.failure()
            Result.WriteFailed("Database did not commit the record")
        } else {
            lastSavedId = id
            lastSavedAtMs = triggerAt
            haptics.success()
            Result.Saved(record, record.notes)
        }
    }

    /** Delete the most recent save made through this pipeline. Returns false when there is none. */
    suspend fun undoLast(): Boolean {
        val id = lastSavedId
        if (id <= 0L) return false
        val deleted = withContext(Dispatchers.IO) {
            runCatching {
                repository.delete(id)
                true
            }.getOrDefault(false)
        }
        if (deleted) {
            lastSavedId = 0L
            lastSavedAtMs = 0L
        }
        return deleted
    }

    private fun buildWarning(fix: LocationAcquirer.Fix, settings: AppSettings): String? {
        val parts = mutableListOf<String>()
        if (fix.source == LocationSource.FALLBACK) {
            parts += "Stale fix used after GPS timeout (age ${fix.ageSeconds ?: 0}s)"
        }
        val accuracy = fix.accuracyMeters
        if (accuracy != null && accuracy > settings.maxAccuracyMeters) {
            parts += "Poor GPS accuracy (${accuracy.toInt()} m)"
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }
}
