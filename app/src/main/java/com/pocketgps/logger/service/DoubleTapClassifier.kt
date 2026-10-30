package com.pocketgps.logger.service

/**
 * Pure Kotlin classifier for deliberate two-impact pocket gestures.
 *
 * Sensor adapters supply the magnitude of linear acceleration in m/s². Keeping this class
 * Android-free makes thresholds and timing independently testable.
 */
internal class DoubleTapClassifier(
    private val threshold: Float = 8.5f,
    private val minGapMs: Long = 110L,
    private val maxGapMs: Long = 520L,
    private val cooldownMs: Long = 1_000L,
) {
    private var firstTapAt = Long.MIN_VALUE
    private var lastAcceptedAt = Long.MIN_VALUE

    fun onImpact(magnitude: Float, elapsedMs: Long): Boolean {
        if (!magnitude.isFinite() || magnitude < threshold) return false
        if (lastAcceptedAt != Long.MIN_VALUE && elapsedMs - lastAcceptedAt < cooldownMs) {
            return false
        }

        val previous = firstTapAt
        firstTapAt = elapsedMs
        if (previous == Long.MIN_VALUE) return false

        val gap = elapsedMs - previous
        if (gap in minGapMs..maxGapMs) {
            firstTapAt = Long.MIN_VALUE
            lastAcceptedAt = elapsedMs
            return true
        }
        // A too-fast pair is likely one noisy impact; a too-slow pair starts a new window.
        return false
    }

    fun reset() {
        firstTapAt = Long.MIN_VALUE
        lastAcceptedAt = Long.MIN_VALUE
    }
}
