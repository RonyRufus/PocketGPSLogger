package com.pocketgps.logger.audio

/**
 * Continuous PCM pipeline. The microphone and its preallocated buffers stay alive while Vosk
 * switches between grammar-constrained hotword detection and short, on-demand transcription.
 * GPS/database work is always handed off to callbacks and never blocks this audio loop.
 */
class RecognitionPipeline(
    private val audio: PcmAudioSource,
    private val spotter: KeywordSpotter,
    initialTriggerPhrase: String,
    private val chunkSize: Int = 2048,
    private val clock: () -> Long,
    cooldownSeconds: Int = 3,
) {
    var onTrigger: ((triggerNanos: Long, phrase: String, confidence: Float) -> Unit)? = null
    var onTranscript: ((String) -> Unit)? = null
    var onPartial: ((String?) -> Unit)? = null
    var onMuteDetected: (() -> Unit)? = null
    var onMuteCleared: (() -> Unit)? = null
    var onSourceError: ((String) -> Unit)? = null
    var onChunkProcessed: ((processingMillis: Float, rms: Float) -> Unit)? = null

    /** Enable the automatic follow-up note capture for production; disabled in legacy unit tests. */
    @Volatile var transcriptionEnabled: Boolean = false

    /** Drain real mic data but feed silence while confirmation audio is playing. */
    var isSuppressed: () -> Boolean = { false }
    var minConfidence: Float = 0f
    @Volatile var cooldownSeconds: Int = cooldownSeconds

    @Volatile private var triggerPhrase: String = initialTriggerPhrase
    @Volatile private var triggerTokens: List<String> = GrammarJson.wordsOf(initialTriggerPhrase)
    @Volatile private var pendingTriggerPhrase: String? = null

    private val buffer = ShortArray(chunkSize)
    private val zeros = ShortArray(chunkSize)
    private val recentWords = ArrayDeque<RecognitionEvent>()
    private val maxRecentSeconds = 4f

    private var cooldownUntilNs = 0L
    private var consecutiveZeroChunks = 0
    private var muteFired = false
    private var transcriptionStartedNs = 0L

    var muteChunksThreshold: Int = 24
    val isCoolingDown: Boolean get() = clock() < cooldownUntilNs

    private companion object {
        const val TRANSCRIPTION_MAX_NS = 12_000_000_000L
        const val TRANSCRIPTION_NO_SPEECH_TIMEOUT_NS = 4_000_000_000L
    }

    fun processOneChunk(): Boolean {
        applyPendingTrigger()
        val n = audio.read(buffer, 0, chunkSize)
        if (n <= 0) {
            if (audio.isExhausted()) return false
            onSourceError?.invoke("AudioRecord read returned $n")
            return false
        }

        trackMute(n)
        val t0 = clock()
        val suppressed = isSuppressed()
        val events = if (suppressed) {
            spotter.acceptWaveForm(zeros, 0, n)
        } else {
            spotter.acceptWaveForm(buffer, 0, n)
        }
        val elapsedMs = (clock() - t0) / 1_000_000f
        val rms = computeRms(n)
        onChunkProcessed?.invoke(elapsedMs, rms)

        if (spotter.isTranscribing) {
            finishTranscriptionIfReady()
            return true
        }

        if (events.isNotEmpty()) {
            handleFinalWords(events)
        } else if (triggerTokens.isNotEmpty()) {
            onPartial?.invoke(spotter.partialResult())
        }
        return true
    }

    private fun finishTranscriptionIfReady() {
        val elapsed = clock() - transcriptionStartedNs
        val endpoint = spotter.transcriptionEndpointDetected
        val timedOut = elapsed >= TRANSCRIPTION_MAX_NS ||
            (elapsed >= TRANSCRIPTION_NO_SPEECH_TIMEOUT_NS && !hasTranscriptText())
        if (!endpoint && !timedOut) return

        val transcript = runCatching { spotter.finishTranscription() }.getOrDefault("").trim()
        if (transcript.isNotEmpty()) onTranscript?.invoke(transcript)
        // finishTranscription restores the grammar recognizer before this method returns.
    }

    private fun hasTranscriptText(): Boolean =
        !spotter.partialResult().isNullOrBlank()

    private fun handleFinalWords(events: List<RecognitionEvent>) {
        events.forEach { recentWords.addLast(it) }
        val nowSec = spotter.streamSeconds()
        while (recentWords.isNotEmpty() &&
            nowSec - recentWords.first().endStreamSeconds > maxRecentSeconds
        ) {
            recentWords.removeFirst()
        }
        if (recentWords.isEmpty()) return

        val words = recentWords.map { it.word }
        if (!TriggerMatcher.matchesTokens(words, triggerTokens)) return
        val confidence = meanConfidenceOfTail(triggerTokens.size)
        if (confidence < minConfidence) return

        val nowNs = clock()
        if (nowNs < cooldownUntilNs) {
            recentWords.clear()
            return
        }

        cooldownUntilNs = nowNs + cooldownSeconds * 1_000_000_000L
        recentWords.clear()
        onTrigger?.invoke(nowNs, TriggerMatcher.normalize(triggerPhrase), confidence)

        // Switch only after the hotword JNI call has returned. The same AudioRecord and PCM
        // buffers are reused; only the native Vosk decoder changes mode.
        if (transcriptionEnabled && spotter.beginTranscription()) {
            transcriptionStartedNs = clock()
        }
    }

    fun updateTrigger(newPhrase: String) {
        pendingTriggerPhrase = newPhrase
    }

    private fun applyPendingTrigger() {
        val phrase = pendingTriggerPhrase ?: return
        pendingTriggerPhrase = null
        triggerPhrase = phrase
        triggerTokens = GrammarJson.wordsOf(phrase)
        recentWords.clear()
    }

    private fun meanConfidenceOfTail(count: Int): Float {
        if (recentWords.isEmpty()) return 0f
        val start = maxOf(0, recentWords.size - count)
        var sum = 0.0
        var countFound = 0
        for (event in recentWords.drop(start)) {
            sum += event.confidence.toDouble()
            countFound++
        }
        return if (countFound == 0) 0f else (sum / countFound).toFloat()
    }

    private fun trackMute(n: Int) {
        var allZero = true
        for (i in 0 until n) {
            if (buffer[i].toInt() != 0) {
                allZero = false
                break
            }
        }
        if (allZero) {
            consecutiveZeroChunks++
            if (consecutiveZeroChunks == muteChunksThreshold) {
                muteFired = true
                onMuteDetected?.invoke()
            }
        } else {
            consecutiveZeroChunks = 0
            if (muteFired) {
                muteFired = false
                onMuteCleared?.invoke()
            }
        }
    }

    fun run(shouldContinue: () -> Boolean) {
        audio.start()
        while (shouldContinue()) {
            if (!processOneChunk()) return
        }
    }

    private fun computeRms(n: Int): Float {
        if (n == 0) return 0f
        var sum = 0.0
        for (i in 0 until n) {
            val sample = buffer[i].toDouble()
            sum += sample * sample
        }
        return (Math.sqrt(sum / n) / Short.MAX_VALUE).toFloat()
    }
}
