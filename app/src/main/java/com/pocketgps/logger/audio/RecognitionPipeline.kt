package com.pocketgps.logger.audio

/**
 * The continuous recognition loop. Runs on a dedicated IO thread for the whole listening
 * session and is shared between production (live mic + Vosk) and deterministic unit tests
 * (synthetic PCM + scripted spotter).
 *
 * Performance contract:
 *  - [buffer] is allocated once; the per-chunk path performs no heap allocation.
 *  - JSON parsing happens only when Vosk emits a final result.
 *  - GPS work is **never** done here; [onTrigger] merely hands off to the service.
 *
 * Duplicate suppression:
 *  - Final-result words are kept in a short rolling buffer so a phrase split across two Vosk
 *    segments still matches.
 *  - A matched phrase clears the buffer and starts a cooldown, so one utterance can never
 *    create two records.
 */
class RecognitionPipeline(
    private val audio: PcmAudioSource,
    private val spotter: KeywordSpotter,
    initialTriggerPhrase: String,
    private val chunkSize: Int = 2048,
    /** Monotonic nanoseconds provider (SystemClock.elapsedRealtimeNanos in production). */
    private val clock: () -> Long,
    cooldownSeconds: Int = 3,
) {

    /** Fired once per accepted trigger. Must return quickly; do GPS work off-thread. */
    var onTrigger: ((triggerNanos: Long, phrase: String, confidence: Float) -> Unit)? = null

    var onPartial: ((String?) -> Unit)? = null
    var onMuteDetected: (() -> Unit)? = null
    var onMuteCleared: (() -> Unit)? = null
    var onSourceError: ((String) -> Unit)? = null
    var onChunkProcessed: ((processingMillis: Float, rms: Float) -> Unit)? = null

    /** When true, real mic data is drained but digital silence is fed to the recognizer. */
    var isSuppressed: () -> Boolean = { false }

    /** Minimum mean confidence for the matched words. 0 = accept any lattice output. */
    var minConfidence: Float = 0f

    /** Post-trigger cooldown; can be changed live from settings. */
    @Volatile
    var cooldownSeconds: Int = cooldownSeconds

    @Volatile
    private var triggerPhrase: String = initialTriggerPhrase

    @Volatile
    private var triggerTokens: List<String> = GrammarJson.wordsOf(initialTriggerPhrase)

    /** Set from any thread; applied at the start of the next chunk on the audio thread. */
    @Volatile
    private var pendingTriggerPhrase: String? = null

    private val buffer = ShortArray(chunkSize)
    private val zeros = ShortArray(chunkSize)

    private val recentWords = ArrayDeque<RecognitionEvent>()
    private val maxRecentSeconds = 4f

    private var cooldownUntilNs = 0L
    private var consecutiveZeroChunks = 0
    private var muteFired = false

    /** Number of consecutive all-zero chunks treated as "mic muted / privacy blocked". */
    var muteChunksThreshold: Int = 24 // ~3 s at 128 ms chunks

    /** True while the pipeline must ignore triggers (post-save cooldown). */
    val isCoolingDown: Boolean get() = clock() < cooldownUntilNs

    /**
     * Process exactly one audio chunk. Returns false when the source errored or is exhausted.
     */
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
            // Keep the Vosk stream clock advancing without feeding our own cue audio.
            spotter.acceptWaveForm(zeros, 0, n)
        } else {
            spotter.acceptWaveForm(buffer, 0, n)
        }
        val elapsedMs = (clock() - t0) / 1_000_000f
        onChunkProcessed?.invoke(elapsedMs, computeRms(n))

        if (events.isNotEmpty()) {
            handleFinalWords(events)
        } else if (triggerTokens.isNotEmpty()) {
            onPartial?.invoke(spotter.partialResult())
        }
        return true
    }

    private fun handleFinalWords(events: List<RecognitionEvent>) {
        events.forEach { recentWords.addLast(it) }
        val nowSec = spotter.streamSeconds()
        while (recentWords.isNotEmpty() && nowSec - recentWords.first().endStreamSeconds > maxRecentSeconds) {
            recentWords.removeFirst()
        }
        if (recentWords.isEmpty()) return

        val words = recentWords.map { it.word }
        if (!TriggerMatcher.matchesTokens(words, triggerTokens)) return

        // Match found. Compute confidence only over the tail words that form the phrase.
        val confidence = meanConfidenceOfTail(triggerTokens.size)
        if (confidence < minConfidence) return

        val nowNs = clock()
        if (nowNs < cooldownUntilNs) {
            // Still cooling down: swallow the duplicate but consume the words.
            recentWords.clear()
            return
        }

        cooldownUntilNs = nowNs + cooldownSeconds * 1_000_000_000L
        recentWords.clear()
        onTrigger?.invoke(nowNs, TriggerMatcher.normalize(triggerPhrase), confidence)
    }

    /** Request a live trigger-phrase change; applied safely on the audio thread. */
    fun updateTrigger(newPhrase: String) {
        pendingTriggerPhrase = newPhrase
    }

    /** Must only be called from the audio thread (inside [processOneChunk]). */
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
        val slice = recentWords.drop(start)
        if (slice.isEmpty()) return 0f
        return slice.sumOf { it.confidence.toDouble() }.toFloat() / slice.size
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

    /** Blocking loop until [shouldContinue] is false or the source errors/exhausts. */
    fun run(shouldContinue: () -> Boolean) {
        audio.start()
        while (shouldContinue()) {
            if (!processOneChunk()) return
        }
    }

    /** Normalised RMS in [0, 1], computed inline without allocation. */
    private fun computeRms(n: Int): Float {
        if (n == 0) return 0f
        var sum = 0.0
        for (i in 0 until n) {
            val s = buffer[i].toDouble()
            sum += s * s
        }
        return (Math.sqrt(sum / n) / Short.MAX_VALUE).toFloat()
    }
}
