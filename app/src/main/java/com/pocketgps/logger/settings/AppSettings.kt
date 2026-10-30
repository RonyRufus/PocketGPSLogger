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
    /**
     * One or more trigger phrases. Any of them saves a spot; each must be at least two words
     * (enforced by [com.pocketgps.logger.audio.TriggerMatcher.isValidTrigger] at write time).
     */
    val triggerPhrases: List<String> = listOf(DEFAULT_TRIGGER_PHRASE),
    val confirmationMode: ConfirmationMode = ConfirmationMode.SPEECH,
    val gpsTimeoutSeconds: Int = 15,
    val cachedLocationPolicy: CachedLocationPolicy = CachedLocationPolicy.PREFER_RECENT,
    /** Maximum age of a cached fix that may be used without an explicit stale warning. */
    val cachedMaxAgeSeconds: Int = 10,
    /** Fixes worse than this are treated as unusable. */
    val maxAccuracyMeters: Float = 150f,
    /** Post-trigger cooldown to swallow duplicate recognitions of one utterance. */
    val cooldownSeconds: Int = 3,
    /**
     * While listening, keep a low-duty-cycle fused-location subscription alive so a trigger
     * saves in ~1 s instead of waiting for a cold GPS fix. Costs a little battery, saves a lot
     * of waiting. The subscription uses balanced power accuracy, not continuous high accuracy.
     */
    val gpsPreWarm: Boolean = true,
    /** Vibrate once on a saved spot and twice on failure. Useful when cues can't be heard. */
    val hapticFeedback: Boolean = true,
    /**
     * Dictation mode: the recognizer runs unconstrained so an utterance can be
     * "trigger + observation" ("save spot, new apartment near the junction"). When off, the
     * constrained grammar listens for the trigger words only (lowest CPU, best trigger
     * accuracy, no notes).
     */
    val voiceNotes: Boolean = true,
    /** Enable experimental double-tap capture while hands-free service is active. */
    val pocketTapEnabled: Boolean = true,
) {
    /** Display helper: the first configured trigger. */
    val primaryTrigger: String get() = triggerPhrases.firstOrNull() ?: DEFAULT_TRIGGER_PHRASE

    /** Backwards-compatible display alias for older UI/state consumers. */
    val triggerPhrase: String get() = primaryTrigger

    companion object {
        const val DEFAULT_TRIGGER_PHRASE = "save spot"
        const val MIN_GPS_TIMEOUT_SECONDS = 5
        const val MAX_GPS_TIMEOUT_SECONDS = 60
        const val MIN_COOLDOWN_SECONDS = 1
        const val MAX_COOLDOWN_SECONDS = 15
    }
}
