package com.pocketgps.logger.audio

import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Reuses one loaded Vosk model for two mutually exclusive modes:
 *  - low-cost grammar-constrained hotword detection while idle;
 *  - unrestricted offline transcription after a hotword.
 *
 * Only one native Recognizer exists at a time. Switching modes closes the old native decoder
 * before creating the next one, keeping peak native memory lower on modest phones.
 */
class VoskKeywordSpotter(
    private val model: Model,
    override val sampleRate: Int = MicAudioSource.SAMPLE_RATE,
    initialPhrases: List<String>,
) : KeywordSpotter {

    private var phrases: List<String> = initialPhrases.toList()
    private var recognizer: Recognizer = newKeywordRecognizer(phrases)
    private var streamBaseSamples: Long = 0L
    private var totalSamplesFed: Long = 0L
    @Volatile private var transcribing = false
    @Volatile private var endpointDetected = false
    private val transcriptParts = ArrayList<String>(4)

    override val isTranscribing: Boolean
        get() = transcribing

    override val transcriptionEndpointDetected: Boolean
        get() = endpointDetected

    private fun newKeywordRecognizer(phrases: List<String>): Recognizer =
        Recognizer(model, sampleRate.toFloat(), GrammarJson.build(phrases)).apply {
            setWords(true)
        }

    private fun newTranscriptionRecognizer(): Recognizer =
        Recognizer(model, sampleRate.toFloat()).apply {
            setWords(false)
        }

    @Synchronized
    override fun acceptWaveForm(pcm: ShortArray, offset: Int, length: Int): List<RecognitionEvent> {
        val rec = recognizer
        val isFinal = if (offset == 0) {
            rec.acceptWaveForm(pcm, length)
        } else {
            rec.acceptWaveForm(pcm.copyOfRange(offset, offset + length), length)
        }
        totalSamplesFed += length

        if (transcribing) {
            endpointDetected = isFinal
            if (isFinal) appendTranscriptText(rec.result)
            return emptyList()
        }

        if (!isFinal) return emptyList()
        val baseSeconds = streamBaseSamples.toFloat() / sampleRate
        return VoskResultParser.parseFinal(rec.result).map { event ->
            event.copy(
                startStreamSeconds = event.startStreamSeconds + baseSeconds,
                endStreamSeconds = event.endStreamSeconds + baseSeconds,
            )
        }
    }

    private fun appendTranscriptText(json: String) {
        val text = runCatching { JSONObject(json).optString("text").trim() }.getOrDefault("")
        if (text.isNotEmpty()) transcriptParts.add(text)
    }

    @Synchronized
    override fun partialResult(): String? = runCatching {
        if (transcribing) {
            JSONObject(recognizer.partialResult).optString("partial").trim().takeIf { it.isNotEmpty() }
        } else {
            VoskResultParser.parsePartial(recognizer.partialResult)
        }
    }.getOrNull()

    @Synchronized
    override fun beginTranscription(): Boolean {
        if (transcribing) return true
        // Release the grammar decoder before allocating the transcription decoder to reduce
        // peak native memory on lower-RAM phones.
        val old = recognizer
        runCatching { old.close() }
        val next = runCatching { newTranscriptionRecognizer() }.getOrNull()
        if (next == null) {
            recognizer = newKeywordRecognizer(phrases)
            return false
        }
        recognizer = next
        transcribing = true
        endpointDetected = false
        transcriptParts.clear()
        return true
    }

    /**
     * Finish the note and immediately return to the lightweight hotword grammar.
     * Any remaining decoder text is included before the recognizer is replaced.
     */
    @Synchronized
    override fun finishTranscription(): String {
        if (!transcribing) return ""
        appendTranscriptText(runCatching { recognizer.finalResult }.getOrDefault("{}"))
        val transcript = transcriptParts.joinToString(" ").replace(Regex("\\s+"), " ").trim()
        val old = recognizer
        runCatching { old.close() }
        transcribing = false
        endpointDetected = false
        transcriptParts.clear()
        streamBaseSamples = totalSamplesFed
        // Close the transcription decoder before restoring the lightweight grammar decoder.
        recognizer = newKeywordRecognizer(phrases)
        return transcript
    }

    /** Total audio seconds consumed since the session began (continuous clock). */
    override fun streamSeconds(): Float = totalSamplesFed.toFloat() / sampleRate

    @Synchronized
    override fun updateGrammar(phrases: List<String>) {
        this.phrases = phrases.toList()
        if (transcribing) return
        val old = recognizer
        streamBaseSamples = totalSamplesFed
        recognizer = newKeywordRecognizer(this.phrases)
        runCatching { old.close() }
    }

    @Synchronized
    override fun close() {
        runCatching { recognizer.close() }
        transcribing = false
    }
}

/**
 * Minimal interface so RecognitionPipeline can be tested with a scripted fake spotter.
 * Default transcription methods keep older test doubles source-compatible.
 */
interface KeywordSpotter {
    val sampleRate: Int
    val isTranscribing: Boolean get() = false
    val transcriptionEndpointDetected: Boolean get() = false
    fun acceptWaveForm(pcm: ShortArray, offset: Int, length: Int): List<RecognitionEvent>
    fun partialResult(): String?
    fun streamSeconds(): Float
    fun updateGrammar(phrases: List<String>)
    fun beginTranscription(): Boolean = false
    fun finishTranscription(): String = ""
    fun close()
}
