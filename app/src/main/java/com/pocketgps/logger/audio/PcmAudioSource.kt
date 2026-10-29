package com.pocketgps.logger.audio

/**
 * A source of 16-bit mono PCM audio.
 *
 * Implementations must be cheap on the hot path: [read] writes into a caller-supplied buffer
 * and returns the number of samples read, or a negative value on error.
 */
interface PcmAudioSource {
    val sampleRate: Int

    fun start()

    fun read(dest: ShortArray, offset: Int, length: Int): Int

    /** True once the source can never produce more data (finite sources only). */
    fun isExhausted(): Boolean = false

    fun stop()
}
