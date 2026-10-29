package com.pocketgps.logger.audio

/**
 * A single word from a Vosk final result, with lattice timing and confidence.
 */
data class RecognitionEvent(
    val word: String,
    val confidence: Float,
    val startStreamSeconds: Float,
    val endStreamSeconds: Float,
)
