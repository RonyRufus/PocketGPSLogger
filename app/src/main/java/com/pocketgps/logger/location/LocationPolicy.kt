package com.pocketgps.logger.location

/**
 * Pure decision policy for GPS fixes — the single place that decides whether a coordinate may
 * be saved as "fresh", "cached", "fallback", or must be reported unavailable.
 *
 * Extracted from [LocationAcquirer] (which keeps all Android plumbing: FusedLocationProvider,
 * LocationManager, permissions) so the freshness/accuracy rules are unit-testable on the JVM.
 *
 * Contract (must stay consistent with the tests):
 *  - A fix without a valid positive timestamp is never trusted; its age is treated as 0 but
 *    its invalid timestamp disqualifies "recent" checks.
 *  - Accuracy is reported by the provider; null means "no usable accuracy" (raw value <= 0 or
 *    NaN), which never fails an accuracy check by itself.
 *  - Ages are evaluated with the caller-supplied [nowMs] at the moment the decision is made,
 *    not when the trigger fired — GPS acquisition can wait up to the timeout.
 *  - A fix whose timestamp is older than `cachedMaxAgeSeconds` is stale; a "fresh" API result
 *    is held to the same limit, because one-shot APIs may answer from a cache.
 *  - Beyond [ABSURD_ACCURACY_METERS] a fix is a guess, not a location.
 */
object LocationPolicy {

    /** Beyond this, a fix is not a location so much as a guess. */
    const val ABSURD_ACCURACY_METERS = 1000f

    /**
     * One fix under evaluation. [accuracyMeters] is the usable accuracy (null when the raw
     * provider value was <= 0 or NaN); [rawAccuracyMeters] keeps the provider's raw value so
     * fresh-vs-cached comparisons behave exactly as before filtering.
     */
    data class FixCandidate(
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Float?,
        val rawAccuracyMeters: Float,
        val timestampMs: Long,
    ) {
        val hasValidTimestamp: Boolean get() = timestampMs > 0L

        /** Age in seconds at [nowMs]; 0 when the timestamp is invalid (never negative). */
        fun ageSeconds(nowMs: Long): Int {
            if (!hasValidTimestamp) return 0
            val ageMs = (nowMs - timestampMs).coerceAtLeast(0L)
            return (ageMs / 1000L).toInt()
        }
    }

    /** A fix the policy has accepted, ready to persist. */
    data class AcceptedFix(
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Float?,
        val fixTimestampMs: Long,
        val source: LocationSource,
        val ageSeconds: Int,
    )

    sealed interface Decision {
        data class Accept(val fix: AcceptedFix) : Decision
        data class Unavailable(val reason: String) : Decision
    }

    private fun FixCandidate.accept(source: LocationSource, ageSeconds: Int, nowMs: Long) =
        AcceptedFix(
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = accuracyMeters,
            // An invalid timestamp must never be persisted as a fix time.
            fixTimestampMs = timestampMs.takeIf { it > 0L } ?: nowMs,
            source = source,
            ageSeconds = ageSeconds,
        )

    private fun isAccurateEnough(accuracyMeters: Float?, maxAccuracyMeters: Float): Boolean =
        accuracyMeters == null || accuracyMeters <= maxAccuracyMeters

    /**
     * Phase 1: the cached fix may satisfy the trigger outright under PREFER_RECENT.
     * Returns null when no acceptable cached fix exists and a fresh request is warranted.
     */
    fun cachedImmediate(cached: FixCandidate?, nowMs: Long, cachedMaxAgeSeconds: Int, maxAccuracyMeters: Float): AcceptedFix? {
        if (cached == null) return null
        val age = cached.ageSeconds(nowMs)
        val recentEnough = cached.hasValidTimestamp && age <= cachedMaxAgeSeconds
        val accurateEnough = isAccurateEnough(cached.accuracyMeters, maxAccuracyMeters)
        return if (recentEnough && accurateEnough) {
            cached.accept(LocationSource.CACHED, age, nowMs)
        } else {
            null
        }
    }

    /**
     * Phase 2: a fix came back from the one-shot fresh request. It is only labelled fresh when
     * its timestamp is valid, recent, and its accuracy is usable — a one-shot API may still
     * answer from a cache, so freshness is verified, never assumed.
     */
    fun judgeFresh(
        fresh: FixCandidate,
        cached: FixCandidate?,
        nowMs: Long,
        cachedMaxAgeSeconds: Int,
        maxAccuracyMeters: Float,
    ): Decision {
        val freshAge = fresh.ageSeconds(nowMs)
        if (!fresh.hasValidTimestamp || freshAge > cachedMaxAgeSeconds) {
            return Decision.Unavailable(
                "GPS provider returned a stale fix (${freshAge}s old; limit ${cachedMaxAgeSeconds}s)",
            )
        }
        val accuracy = fresh.accuracyMeters
        if (isAccurateEnough(accuracy, maxAccuracyMeters)) {
            return Decision.Accept(fresh.accept(LocationSource.FRESH, freshAge, nowMs))
        }
        // Fresh but poor accuracy. Prefer a better cached fix if one exists, else return the
        // fresh fix (the UI flags poor accuracy) unless it is clearly meaningless.
        if (cached != null) {
            val cachedAge = cached.ageSeconds(nowMs)
            val cachedAccuracy = cached.accuracyMeters
            val cachedIsUsable = cached.hasValidTimestamp && cachedAge <= cachedMaxAgeSeconds &&
                (cachedAccuracy == null || cachedAccuracy <= maxAccuracyMeters) &&
                (cachedAccuracy == null || cached.rawAccuracyMeters <= fresh.rawAccuracyMeters)
            if (cachedIsUsable) {
                return Decision.Accept(cached.accept(LocationSource.FALLBACK, cachedAge, nowMs))
            }
        }
        if (accuracy == null || accuracy <= ABSURD_ACCURACY_METERS) {
            return Decision.Accept(fresh.accept(LocationSource.FRESH, fresh.ageSeconds(nowMs), nowMs))
        }
        return Decision.Unavailable("Fresh fix accuracy too poor (${accuracy.toInt()} m)")
    }

    /**
     * Phase 3: the fresh request timed out. Old or inaccurate coordinates are refused rather
     * than quietly saved as if they were current — a note is better than a misleading fix.
     */
    fun fallbackAfterTimeout(
        cached: FixCandidate?,
        nowMs: Long,
        cachedMaxAgeSeconds: Int,
        maxAccuracyMeters: Float,
        timeoutSeconds: Int,
    ): Decision {
        if (cached == null) {
            return Decision.Unavailable("No fresh GPS fix within ${timeoutSeconds}s")
        }
        val age = cached.ageSeconds(nowMs)
        val accuracy = cached.accuracyMeters
        val recentEnough = cached.hasValidTimestamp && age <= cachedMaxAgeSeconds
        val accurateEnough = isAccurateEnough(accuracy, maxAccuracyMeters)
        if (recentEnough && accurateEnough) {
            return Decision.Accept(cached.accept(LocationSource.FALLBACK, age, nowMs))
        }
        val reasons = buildList {
            if (!cached.hasValidTimestamp) add("last fix has no valid timestamp")
            else if (!recentEnough) add("last fix is ${age}s old (limit ${cachedMaxAgeSeconds}s)")
            if (!accurateEnough && accuracy != null) {
                add("last fix accuracy is ${accuracy.toInt()} m (limit ${maxAccuracyMeters.toInt()} m)")
            }
        }
        return Decision.Unavailable(
            "Fresh GPS fix timed out; refusing stale/poor fallback: ${reasons.joinToString("; ")}",
        )
    }
}
