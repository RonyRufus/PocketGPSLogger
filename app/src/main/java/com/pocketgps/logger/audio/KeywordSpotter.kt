package com.pocketgps.logger.audio

/**
 * Recognition backend used by [RecognitionPipeline]. Kept free of Android/Vosk types so the
 * pipeline can be tested with a scripted fake.
 *
 * Two independent jobs:
 *  1. **Trigger spotting** ([acceptWaveForm]): always a constrained grammar, in every mode, so
 *     the trigger is detected identically whether voice notes are on or off.
 *  2. **Note transcription** ([beginNote] / [acceptNoteAudio] / [finishNote]): an unconstrained
 *     pass over only the audio that follows a matched trigger. Never used for spotting.
 */
interface KeywordSpotter {
    val sampleRate: Int
    fun acceptWaveForm(pcm: ShortArray, offset: Int, length: Int): List<RecognitionEvent>
    fun partialResult(): String?
    fun streamSeconds(): Float
    fun updateGrammar(phrases: List<String>)

    /**
     * Prepare (true) or release (false) the note transcriber. Must NOT touch the trigger
     * recogniser, so toggling voice notes never disturbs trigger detection or loses audio.
     */
    fun setDictation(enabled: Boolean)

    /** Start a fresh note transcription. False when unavailable (caller saves location-only). */
    fun beginNote(): Boolean = false

    /** Feed note audio. Words are accumulated internally until [finishNote]. */
    fun acceptNoteAudio(pcm: ShortArray, offset: Int, length: Int) = Unit

    /** Flush and return every word transcribed since [beginNote]. */
    fun finishNote(): List<RecognitionEvent> = emptyList()

    /** Discard an in-progress note transcription. */
    fun cancelNote() = Unit

    fun close()
}
