package com.pocketgps.logger.sensor

import kotlin.math.abs

/**
 * Pure-Kotlin double-tap detector for "tap the pocketed phone" input.
 *
 * First principles: a deliberate tap through clothing is an *isolated impulse* — one short,
 * sharp acceleration spike, then quiet, then a second spike within a few hundred milliseconds.
 * Everything else a pocket produces (walking sway, cloth rub, bike rumble, sitting down) is
 * *sustained* energy or isolated low-frequency wobble. So instead of thresholding raw magnitude,
 * we classify the *pattern* of spikes:
 *
 *  - a **spike** is a sample whose |acceleration| (in g) crosses [spikeG];
 *  - a **double tap** is exactly two spikes separated by [gapMinMs]..[gapMaxMs], followed by
 *    [settleMs] of quiet before the pattern confirms;
 *  - more than [maxSpikeRun] spikes inside the window means sustained vibration (engine, bike,
 *    cloth rubbing) — the pattern is cancelled, not saved;
 *  - a confirmed double tap opens a [debounceMs] window so one tap cannot save twice;
 *  - isolated single spikes are deliberately ignored (a single tap is too easy to trigger by
 *    accident) — but they are counted, so the calibration screen can show raw input.
 *
 * The class holds no Android types and takes an injectable millisecond clock, so the whole
 * detector is unit-testable and runs identically in tests, the calibration screen and the
 * foreground service.
 */
class TapDetector(
    /** Acceleration magnitude (in g) at or above which a sample counts as a spike. */
    val spikeG: Float = 2.2f,
    /** Minimum spacing between the two spikes of a double tap (bounces inside this are one tap). */
    val gapMinMs: Long = 50,
    /** Maximum spacing between the two spikes of a double tap. */
    val gapMaxMs: Long = 380,
    /** Quiet time after the second spike before the double tap is confirmed. */
    val settleMs: Long = 420,
    /** Silence after a confirmed double tap during which new spikes are ignored. */
    val debounceMs: Long = 1_200,
    /**
     * Spikes allowed inside one open pattern before it is classified as sustained vibration
     * (walking shake, cloth rub, engine) and cancelled.
     */
    val maxSpikeRun: Int = 3,
    private val clock: () -> Long,
) {

    /** Finite states of one gesture attempt. */
    private enum class State { IDLE, ONE_SPIKE, CONFIRMING, COOLDOWN }

    private var state = State.IDLE

    private var firstSpikeAtMs = 0L
    private var secondSpikeAtMs = 0L
    private var spikeRun = 0
    private var stateEnteredAtMs = 0L

    /** Total confirmed double taps since construction. */
    var doubleTaps: Long = 0L
        private set

    /** Total raw spikes seen (every sample above [spikeG]) — for calibration displays. */
    var spikesSeen: Long = 0L
        private set

    /** Timestamp of the most recent confirmed double tap (clock domain of [clock]), 0 if none. */
    var lastDoubleTapAtMs: Long = 0L
        private set

    /**
     * Feed one accelerometer sample. [magnitudeG] is |x,y,z| in g (~1.0 at rest).
     * [timestampMs] must be non-decreasing (event timestamp converted to the clock's domain).
     * Returns the number of confirmed double taps produced by this sample (0 or 1).
     */
    fun feed(timestampMs: Long, magnitudeG: Float): Int {
        if (magnitudeG >= spikeG) {
            spikesSeen++
            return onSpike(timestampMs)
        }
        return onQuiet(timestampMs)
    }

    // ------------------------------------------------------------------ spike handling

    private fun onSpike(nowMs: Long): Int {
        return when (state) {
            State.IDLE -> {
                enterOne(nowMs, nowMs)
                0
            }

            State.ONE_SPIKE -> {
                val spacing = nowMs - firstSpikeAtMs
                when {
                    spacing < gapMinMs -> {
                        // Bounce of the same physical tap (sensor ringing): extend the run.
                        spikeRun++
                        if (spikeRun >= maxSpikeRun) cancel(nowMs)
                        0
                    }

                    spacing <= gapMaxMs -> {
                        // Second spike of a plausible double tap: wait for quiet to confirm.
                        secondSpikeAtMs = nowMs
                        spikeRun = 2
                        state = State.CONFIRMING
                        stateEnteredAtMs = nowMs
                        0
                    }

                    else -> {
                        // Too far apart to be one gesture: this spike starts a new attempt.
                        enterOne(nowMs, nowMs)
                        0
                    }
                }
            }

            State.CONFIRMING -> {
                spikeRun++
                if (spikeRun >= maxSpikeRun) {
                    // Third impact inside the window: rumble / cloth rub, not a tap.
                    cancel(nowMs)
                }
                0
            }

            State.COOLDOWN -> {
                // Debounce elapsed: this spike is the first of a brand-new attempt.
                if (nowMs - stateEnteredAtMs >= debounceMs) enterOne(nowMs, nowMs)
                0
            }
        }
    }

    // ------------------------------------------------------------------ quiet handling

    private fun onQuiet(nowMs: Long): Int {
        return when (state) {
            State.ONE_SPIKE -> {
                // Give up when the window for a second spike has fully elapsed.
                if (nowMs - firstSpikeAtMs > gapMaxMs + settleMs) {
                    state = State.IDLE
                }
                0
            }

            State.CONFIRMING -> {
                if (nowMs - secondSpikeAtMs >= settleMs) {
                    confirm(nowMs)
                    1
                } else {
                    0
                }
            }

            State.COOLDOWN -> {
                if (nowMs - stateEnteredAtMs >= debounceMs) state = State.IDLE
                0
            }

            State.IDLE -> 0
        }
    }

    private fun confirm(nowMs: Long) {
        doubleTaps++
        lastDoubleTapAtMs = nowMs
        state = State.COOLDOWN
        stateEnteredAtMs = nowMs
    }

    private fun cancel(nowMs: Long) {
        state = State.COOLDOWN
        stateEnteredAtMs = nowMs
    }

    private fun enterOne(nowMs: Long, spikeAtMs: Long) {
        state = State.ONE_SPIKE
        firstSpikeAtMs = spikeAtMs
        spikeRun = 1
        stateEnteredAtMs = nowMs
    }

    /** Drop all in-flight pattern state (e.g. when the listener is unregistered/re-registered). */
    fun reset() {
        state = State.IDLE
        spikeRun = 0
        firstSpikeAtMs = 0L
        secondSpikeAtMs = 0L
    }

    companion object {
        /**
         * Spike thresholds per sensitivity preset, in g. A tap through fabric typically peaks
         * around 2–3 g at the sensor; walking sway stays under ~1.5 g on magnitude.
         */
        fun spikeFor(sensitivity: TapSensitivity): Float = when (sensitivity) {
            TapSensitivity.FIRM -> 2.9f
            TapSensitivity.NORMAL -> 2.2f
            TapSensitivity.GENTLE -> 1.7f
        }
    }
}

/** How eager the detector is. Higher sensitivity = lighter taps count, more false positives. */
enum class TapSensitivity { FIRM, NORMAL, GENTLE }
