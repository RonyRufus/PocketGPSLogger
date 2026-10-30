package com.pocketgps.logger.audio

import android.util.Log
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Vosk backend for [KeywordSpotter].
 *
 *  - **Trigger recogniser**: persistent, always grammar-constrained (trigger words + decoys),
 *    created once per session. Voice notes never change it, so trigger accuracy and CPU are the
 *    same with notes on or off. Rebuilt only when the trigger phrases change.
 *  - **Note recogniser**: unconstrained, over the same small model, created when voice notes are
 *    enabled (off the audio thread) and reused via reset() for every capture. It only ever sees
 *    audio that follows a matched trigger.
 */
class VoskKeywordSpotter(
    private val model: Model,
    override val sampleRate: Int = MicAudioSource.SAMPLE_RATE,
    initialPhrases: List<String>,
) : KeywordSpotter {

    companion object {
        private const val TAG = "VoskKeywordSpotter"
    }

    private var phrases: List<String> = initialPhrases
    private var recognizer: Recognizer = newTriggerRecognizer()

    private var noteRecognizer: Recognizer? = null
    private val noteWords = ArrayList<RecognitionEvent>()

    private var streamBaseSamples: Long = 0L
    private var totalSamplesFed: Long = 0L

    private fun newTriggerRecognizer(): Recognizer =
        Recognizer(model, sampleRate.toFloat(), GrammarJson.build(phrases)).apply { setWords(true) }

    private fun newNoteRecognizer(): Recognizer =
        Recognizer(model, sampleRate.toFloat()).apply { setWords(true) }

    @Synchronized
    override fun acceptWaveForm(pcm: ShortArray, offset: Int, length: Int): List<RecognitionEvent> {
        val rec = recognizer
        val isFinal = if (offset == 0) {
            rec.acceptWaveForm(pcm, length)
        } else {
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

    override fun streamSeconds(): Float = totalSamplesFed.toFloat() / sampleRate

    @Synchronized
    override fun updateGrammar(newPhrases: List<String>) {
        if (newPhrases == phrases) return
        val old = recognizer
        phrases = newPhrases
        streamBaseSamples = totalSamplesFed
        recognizer = newTriggerRecognizer()
        runCatching { old.close() }.onFailure { Log.w(TAG, "Failed to close old recognizer", it) }
        Log.d(TAG, "Grammar updated to $newPhrases")
    }

    /** Prepare/release only the note recogniser. The trigger recogniser is never touched. */
    override fun setDictation(enabled: Boolean) {
        if (enabled) {
            if (synchronized(this) { noteRecognizer != null }) return
            // Allocate outside the lock so the audio thread is never stalled by model setup.
            val created = runCatching { newNoteRecognizer() }
                .onFailure { Log.e(TAG, "Failed to create note recogniser", it) }
                .getOrNull() ?: return
            val surplus = synchronized(this) {
                if (noteRecognizer == null) { noteRecognizer = created; null } else created
            }
            surplus?.let { runCatching { it.close() } }
        } else {
            val old = synchronized(this) {
                noteWords.clear()
                noteRecognizer.also { noteRecognizer = null }
            }
            old?.let { runCatching { it.close() } }
        }
        Log.d(TAG, "Note transcriber enabled=$enabled")
    }

    @Synchronized
    override fun beginNote(): Boolean {
        noteWords.clear()
        val rec = noteRecognizer ?: runCatching { newNoteRecognizer() }
            .onFailure { Log.e(TAG, "Note recogniser unavailable", it) }
            .getOrNull()?.also { noteRecognizer = it } ?: return false
        return runCatching { rec.reset(); true }.getOrDefault(false)
    }

    @Synchronized
    override fun acceptNoteAudio(pcm: ShortArray, offset: Int, length: Int) {
        val rec = noteRecognizer ?: return
        val endpoint = if (offset == 0) {
            rec.acceptWaveForm(pcm, length)
        } else {
            rec.acceptWaveForm(pcm.copyOfRange(offset, offset + length), length)
        }
        // Vosk closes a segment at its own endpoint; keep that segment's words or they are lost.
        if (endpoint) noteWords += VoskResultParser.parseFinal(rec.result)
    }

    @Synchronized
    override fun finishNote(): List<RecognitionEvent> {
        val rec = noteRecognizer ?: return emptyList()
        noteWords += VoskResultParser.parseFinal(rec.finalResult)
        val out = ArrayList(noteWords)
        noteWords.clear()
        return out
    }

    @Synchronized
    override fun cancelNote() {
        noteWords.clear()
        runCatching { noteRecognizer?.reset() }
    }

    override fun close() {
        val (a, b) = synchronized(this) {
            Pair(recognizer, noteRecognizer).also { noteRecognizer = null }
        }
        runCatching { a.close() }.onFailure { Log.w(TAG, "Failed to close recognizer", it) }
        b?.let { runCatching { it.close() } }
        Log.d(TAG, "Recognizers closed")
    }
}
