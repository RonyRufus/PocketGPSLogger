package com.pocketgps.logger.location

/**
 * Describes where a saved coordinate came from.
 *
 * Persisted as a stable string in [com.pocketgps.logger.data.LocationRecord.locationSource].
 */
enum class LocationSource(val wire: String) {
    /** A new fix acquired in response to the trigger. */
    FRESH("fresh"),

    /** A cached / last-known fix that was still recent enough. */
    CACHED("cached"),

    /** A last-known fix used only after a fresh request timed out (explicitly stale). */
    FALLBACK("fallback"),

    /** The record carries no location at all (a note saved while GPS was unavailable). */
    NONE("none");

    companion object {
        fun fromWire(value: String?): LocationSource =
            entries.firstOrNull { it.wire == value } ?: FALLBACK
    }
}
