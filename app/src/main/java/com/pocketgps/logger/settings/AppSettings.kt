package com.pocketgps.logger.settings

/**
 * Confirmation cue played after a save completes.
 */
enum class ConfirmationMode {
    /** Speak "Location saved" / "Location unavailable" through TTS. */
    SPEECH,

    /** Short, quiet beep pattern; no TTS. */
    TONE,

    /** No audible cue at all. */
    SILENT,
}

/**
 * Cached-location policy for a trigger.
 */
enum class CachedLocationPolicy {
    /** Use a recent, sufficiently accurate cached fix if one exists; else request fresh. */
    PREFER_RECENT,

    /** Always request a fresh fix; fall back to last-known only after timeout. */
    FRESH_ONLY,
}

/**
 * User-configurable settings. Defaults match the spec ("save spot", short GPS timeout, etc.).
 */
data class AppSettings(
    val triggerPhrase: String = DEFAULT_TRIGGER_PHRASE,
    val confirmationMode: ConfirmationMode = ConfirmationMode.SPEECH,
    val gpsTimeoutSeconds: Int = 15,
    val cachedLocationPolicy: CachedLocationPolicy = CachedLocationPolicy.PREFER_RECENT,
    /** Maximum age of a cached fix that may be used without an explicit stale warning. */
    val cachedMaxAgeSeconds: Int = 10,
    /** Fixes worse than this are treated as unusable. */
    val maxAccuracyMeters: Float = 150f,
    /** Post-trigger cooldown to swallow duplicate recognitions of one utterance. */
    val cooldownSeconds: Int = 3,
) {
    companion object {
        const val DEFAULT_TRIGGER_PHRASE = "save spot"
        const val MIN_GPS_TIMEOUT_SECONDS = 5
        const val MAX_GPS_TIMEOUT_SECONDS = 60
        const val MIN_COOLDOWN_SECONDS = 1
        const val MAX_COOLDOWN_SECONDS = 15
    }
}
