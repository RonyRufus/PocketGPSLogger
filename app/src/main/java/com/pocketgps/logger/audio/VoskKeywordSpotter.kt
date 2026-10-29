package com.pocketgps.logger.audio

import org.vosk.Model
import org.vosk.Recognizer

/**
 * Persistent Vosk recognizer running in grammar-constrained mode over the raw 16 kHz PCM stream.
 *
 * The recognizer is created **once** per listening session and reused for every chunk; it is
 * never torn down between utterances. When the trigger phrase changes, [updateGrammar] swaps in a
 * fresh recognizer while carrying the stream clock forward so timing stays continuous.
 */
class VoskKeywordSpotter(
    private val model: Model,
    override val sampleRate: Int = MicAudioSource.SAMPLE_RATE,
    initialPhrases: List<String>,
) : KeywordSpotter {

    private var recognizer: Recognizer = newRecognizer(initialPhrases)
    private var streamBaseSamples: Long = 0L
    private var totalSamplesFed: Long = 0L

    private fun newRecognizer(phrases: List<String>): Recognizer =
        Recognizer(model, sampleRate.toFloat(), GrammarJson.build(phrases)).apply {
            // Enable word-level metadata so final results carry conf + lattice timing.
            setWords(true)
        }

    /**
     * Feed [length] samples from [pcm] starting at [offset].
     * Returns the parsed final-result words (possibly empty).
     *
     * Synchronised with [updateGrammar]/[close]: a grammar rebuild closes the old native
     * recognizer, so it must never happen while another thread is inside a JNI call.
     */
    @Synchronized
    override fun acceptWaveForm(pcm: ShortArray, offset: Int, length: Int): List<RecognitionEvent> {
        val rec = recognizer
        val isFinal = if (offset == 0) {
            rec.acceptWaveForm(pcm, length)
        } else {
            // Vosk expects a full array; copy the slice (allocation only on the rare offset path).
            rec.acceptWaveForm(pcm.copyOfRange(offset, offset + length), length)
        }
        totalSamplesFed += length
        if (!isFinal) return emptyList()
        val baseSeconds = streamBaseSamples.toFloat() / sampleRate
        return VoskResultParser.parseFinal(rec.result).map { event ->
            event.copy(
                startStreamSeconds = event.startStreamSeconds + baseSeconds,
                endStreamSeconds = event.endStreamSeconds + baseSeconds,
            )
        }
    }

    @Synchronized
    override fun partialResult(): String? = runCatching {
        VoskResultParser.parsePartial(recognizer.partialResult)
    }.getOrNull()

    /** Total audio seconds consumed since the session began (continuous clock). */
    override fun streamSeconds(): Float = totalSamplesFed.toFloat() / sampleRate

    @Synchronized
    override fun updateGrammar(phrases: List<String>) {
        val old = recognizer
        streamBaseSamples = totalSamplesFed
        recognizer = newRecognizer(phrases)
        runCatching { old.close() }
    }

    @Synchronized
    override fun close() {
        runCatching { recognizer.close() }
    }
}

/**
 * Minimal interface so [RecognitionPipeline] can be tested with a scripted fake spotter.
 */
interface KeywordSpotter {
    val sampleRate: Int
    fun acceptWaveForm(pcm: ShortArray, offset: Int, length: Int): List<RecognitionEvent>
    fun partialResult(): String?
    fun streamSeconds(): Float
    fun updateGrammar(phrases: List<String>)
    fun close()
}
