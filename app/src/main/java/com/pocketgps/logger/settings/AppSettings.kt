package com.pocketgps.logger.settings

import com.pocketgps.logger.sensor.TapSensitivity

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
 * User-configurable settings for the tap-first v2 app.
 *
 * The law is efficiency: GPS work only happens around an actual save, and the sensor loop only
 * runs while the phone is (or may be) in use. Defaults reflect that.
 */
data class AppSettings(
    val tapSensitivity: TapSensitivity = TapSensitivity.NORMAL,
    /**
     * When true (default), the accelerometer listener, GPS pre-warm and wake lock are released
     * after the phone has been stationary for a while, and re-armed automatically by the
     * hardware significant-motion sensor. Idle cost drops to almost zero.
     */
    val onlyWhileMoving: Boolean = true,
    val gpsTimeoutSeconds: Int = 15,
    val cachedLocationPolicy: CachedLocationPolicy = CachedLocationPolicy.PREFER_RECENT,
    /** Maximum age of a cached fix that may be used without an explicit stale warning. */
    val cachedMaxAgeSeconds: Int = 10,
    /** Fixes worse than this are treated as unusable. */
    val maxAccuracyMeters: Float = 150f,
    /**
     * While tap logging is active, keep a low-duty-cycle fused-location subscription alive so a
     * tap saves in ~1 s instead of waiting for a cold GPS fix. Uses balanced power accuracy,
     * never continuous high accuracy.
     */
    val gpsPreWarm: Boolean = true,
    /** Vibrate once on a saved spot and twice on failure. The primary feedback channel. */
    val hapticFeedback: Boolean = true,
) {
    companion object {
        const val MIN_GPS_TIMEOUT_SECONDS = 5
        const val MAX_GPS_TIMEOUT_SECONDS = 60
    }
}
