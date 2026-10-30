package com.pocketgps.logger.audio

import android.util.Log
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Persistent Vosk recognizer over the raw 16 kHz PCM stream, supporting two modes:
 *  - **Constrained grammar** (default): only the configured trigger words plus decoys can be
 *    emitted — best trigger accuracy, lowest CPU, but it cannot transcribe a note.
 *  - **Dictation** ([setDictation](true)): an unconstrained recognizer over the same small
 *    model, used for "trigger + note" voice captures. Slightly more CPU per active chunk; the
 *    voice gate keeps the idle cost unchanged.
 *
 * The recognizer is created **once** per listening session and reused for every chunk; it is
 * never torn down between utterances. When the trigger phrases change, [updateGrammar] swaps
 * in a fresh recognizer while carrying the stream clock forward so timing stays continuous.
 */
class VoskKeywordSpotter(
    private val model: Model,
    override val sampleRate: Int = MicAudioSource.SAMPLE_RATE,
    initialPhrases: List<String>,
) : KeywordSpotter {

    companion object {
        private const val TAG = "VoskKeywordSpotter"
    }

    private var dictation = false
    private var phrases: List<String> = initialPhrases

    private var recognizer: Recognizer = newRecognizer()

    private fun newRecognizer(): Recognizer =
        try {
            val grammar = if (dictation) null else GrammarJson.build(phrases)
            val rec = if (grammar == null) {
                Recognizer(model, sampleRate.toFloat())
            } else {
                Recognizer(model, sampleRate.toFloat(), grammar)
            }
            rec.apply { setWords(true) }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to create Vosk Recognizer (dictation=$dictation)", t)
            throw t
        }

    /**
     * Feed [length] samples from [pcm] starting at [offset].
     * Returns the parsed final-result words (possibly empty).
     *
     * Synchronised with [updateGrammar]/[setDictation]/[close]: a grammar rebuild closes the
     * old native recognizer, so it must never happen while another thread is inside a JNI call.
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

    private var streamBaseSamples: Long = 0L
    private var totalSamplesFed: Long = 0L

    @Synchronized
    override fun updateGrammar(newPhrases: List<String>) {
        if (!dictation && newPhrases == phrases) return
        val old = recognizer
        phrases = newPhrases
        streamBaseSamples = totalSamplesFed
        recognizer = newRecognizer()
        runCatching { old.close() }.onFailure { Log.w(TAG, "Failed to close old recognizer", it) }
        Log.d(TAG, "Grammar updated to $newPhrases")
    }

    @Synchronized
    override fun setDictation(enabled: Boolean) {
        if (enabled == dictation) return
        val old = recognizer
        dictation = enabled
        streamBaseSamples = totalSamplesFed
        recognizer = newRecognizer()
        runCatching { old.close() }.onFailure { Log.w(TAG, "Failed to close old recognizer", it) }
        Log.d(TAG, "Dictation mode set to $enabled")
    }

    @Synchronized
    override fun close() {
        runCatching { recognizer.close() }.onFailure { Log.w(TAG, "Failed to close recognizer", it) }
        Log.d(TAG, "Recognizer closed")
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
    /** Switch between the constrained grammar and unconstrained dictation. */
    fun setDictation(enabled: Boolean)
    fun close()
}
