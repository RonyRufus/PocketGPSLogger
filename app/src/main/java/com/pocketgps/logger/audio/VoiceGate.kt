package com.pocketgps.logger.audio

/**
 * Energy-based voice activity gate for the recognition hot loop.
 *
 * Why it exists: `Vosk.acceptWaveForm` is the single most expensive call in the audio loop, and
 * with a pocket-mounted phone it runs against silence most of the time. Feeding silence still
 * costs CPU (and therefore battery) on every chunk. This gate watches the per-chunk RMS and
 * tells the pipeline when the recognizer can safely sleep.
 *
 * Design (all thresholds in normalised RMS, 0..1):
 *  - **Hysteresis**: speech starts when a chunk exceeds [openRms] and stays open while any
 *    chunk exceeds the lower [closeRms]. This avoids chattering around a single threshold.
 *  - **Tail window**: the gate stays open for [tailChunks] chunks after speech stops, so Vosk
 *    can flush its final result — a phrase is only ever recognised at the *end* of an
 *    utterance, and cutting the stream early would swallow it.
 *  - **Open guard**: once the gate has been open for [maxOpenChunks] without a match, it is
 *    force-closed to bound worst-case energy use (e.g. constant wind or engine noise on a ride).
 *
 * The class is pure Kotlin and unit-testable; the pipeline feeds it one RMS value per chunk.
 */
class VoiceGate(
    /** RMS at or above which the gate opens (speech suspected). */
    val openRms: Float = DEFAULT_OPEN_RMS,
    /** RMS at or above which an open gate stays open. */
    val closeRms: Float = DEFAULT_CLOSE_RMS,
    /** Chunks to stay open after the last active chunk (lets Vosk flush finals). */
    val tailChunks: Int = DEFAULT_TAIL_CHUNKS,
    /** Consecutive open chunks after which the gate force-closes (noise guard). */
    val maxOpenChunks: Int = DEFAULT_MAX_OPEN_CHUNKS,
) {

    /** True when the recognizer should be fed this chunk. */
    var open: Boolean = false
        private set

    private var openRunChunks = 0
    private var tailRunChunks = 0

    /**
     * Feed one chunk's RMS. Returns true when the recognizer should be fed (gate open),
     * false when it may sleep for this chunk.
     */
    fun process(rms: Float): Boolean {
        val active = rms >= openRms
        val weaklyActive = rms >= closeRms

        if (active) {
            if (!open) {
                open = true
                openRunChunks = 0
                tailRunChunks = 0
            }
            openRunChunks++
            tailRunChunks = 0
            if (openRunChunks >= maxOpenChunks) {
                // Sustained non-speech energy (wind, engine): stop decoding, wait for quiet.
                forceClose()
                return false
            }
            return true
        }

        if (open) {
            if (weaklyActive) {
                // Between words inside an utterance: keep decoding, keep the guard running.
                openRunChunks++
                tailRunChunks = 0
                if (openRunChunks >= maxOpenChunks) {
                    forceClose()
                    return false
                }
                return true
            }
            // Quiet chunk inside the tail window.
            if (tailRunChunks < tailChunks) {
                tailRunChunks++
                return true
            }
            // Tail exhausted: close until real speech returns.
            open = false
            openRunChunks = 0
            tailRunChunks = 0
            return false
        }
        return false
    }

    /** Close immediately (e.g. suppression window started); the next active chunk reopens. */
    fun forceClose() {
        open = false
        openRunChunks = 0
        tailRunChunks = 0
    }

    companion object {
        /**
         * Calibrated for a pocket / handlebar environment. VOICE_RECOGNITION source applies its
         * own AGC; 0.012 ≈ distant city noise, 0.02 ≈ a voice a metre away. Hysteresis gap
         * avoids flapping around rustle level. 24 chunks ≈ 3 s tail at 128 ms/chunk.
         */
        const val DEFAULT_OPEN_RMS = 0.018f
        const val DEFAULT_CLOSE_RMS = 0.010f
        const val DEFAULT_TAIL_CHUNKS = 24
        const val DEFAULT_MAX_OPEN_CHUNKS = 235 // ≈ 30 s of continuous "speech"
    }
}
