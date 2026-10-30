package com.pocketgps.logger.audio

/**
 * The continuous recognition loop. Runs on a dedicated IO thread for the whole listening
 * session and is shared between production (live mic + Vosk) and deterministic unit tests
 * (synthetic PCM + scripted spotter).
 *
 * Performance contract:
 *  - [buffer] is allocated once; the per-chunk path performs no heap allocation except on the
 *    rare final-result / note-capture events.
 *  - JSON parsing happens only when Vosk emits a final result.
 *  - GPS work is **never** done here; [onTrigger] merely hands off to the service.
 *  - A [VoiceGate] skips the recognizer during sustained silence, so a pocket-mounted phone
 *    spends almost no CPU until something actually sounds like speech.
 *
 * Duplicate suppression:
 *  - Final-result words are kept in a short rolling buffer (wall-clock bounded) so a phrase
 *    split across two Vosk segments still matches.
 *  - A matched phrase clears the buffer and starts a cooldown, so one utterance can never
 *    create two records.
 *
 * Multiple triggers:
 *  - Any configured trigger phrase may fire; [TriggerMatcher.findFirstMatch] resolves which
 *    one matched deterministically (earliest word wins, declaration order breaks ties).
 *
 * Dictation mode (voice notes):
 *  - When dictation is enabled, the spotter runs unconstrained. On a trigger match the pipeline
 *    does not fire immediately; it enters note capture and collects the words that follow the
 *    trigger until the speaker pauses (voice gate closes with words already collected), a
 *    second trigger is heard, the word cap is reached, the capture window elapses, or the
 *    audio source ends. It then fires once, with the dictated note (null when the user said
 *    only the trigger — a location-only save). Words spoken before the trigger are not part of
 *    the note; the complete normalised utterance is returned as the transcript so nothing
 *    recognised is silently discarded.
 *  - With dictation disabled (constrained grammar), the historical behaviour is kept: fire the
 *    moment the trigger is accepted, with a null note.
 */
class RecognitionPipeline(
    private val audio: PcmAudioSource,
    private val spotter: KeywordSpotter,
    initialTriggers: List<String>,
    private val chunkSize: Int = 2048,
    /** Monotonic nanoseconds provider (SystemClock.elapsedRealtimeNanos in production). */
    private val clock: () -> Long,
    cooldownSeconds: Int = 3,
    /** Wall-clock seconds a dictation capture waits for note words after a trigger. */
    private val noteWindowSeconds: Float = 4f,
    /** Hard cap on dictation capture words so a runaway capture cannot grow unbounded. */
    private val maxNoteWords: Int = 64,
) {

    /** Fired once per accepted trigger. Must return quickly; do GPS work off-thread. */
    var onTrigger: ((
        triggerNanos: Long,
        phrase: String,
        confidence: Float,
        note: String?,
        transcript: String?,
    ) -> Unit)? = null

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
    private var triggerPhrases: List<String> = initialTriggers

    @Volatile
    private var triggerTokens: List<List<String>> = initialTriggers.map(GrammarJson::wordsOf)

    /** Unconstrained dictation (voice notes) vs constrained grammar. Audio thread only. */
    private var dictation = false

    /** Set from any thread; applied at the start of the next chunk on the audio thread. */
    @Volatile
    private var pendingTriggers: List<String>? = null

    @Volatile
    private var pendingDictation: Boolean? = null

    private val buffer = ShortArray(chunkSize)
    private val zeros = ShortArray(chunkSize)

    /** Energy gate deciding when the recognizer is worth feeding. Audio thread only. */
    val voiceGate = VoiceGate()

    private val recentWords = ArrayDeque<RecentWord>()
    /** Rolling window in *wall-clock* seconds — not stream time, which stalls while gated. */
    private val maxRecentSeconds = 4f

    /** In-flight dictation capture after a trigger matched; null while just spotting. */
    private var noteCapture: NoteCapture? = null

    private var cooldownUntilNs = 0L
    private var consecutiveZeroChunks = 0
    private var muteFired = false
    private var lastGateOpen = false

    /** Number of consecutive all-zero chunks treated as "mic muted / privacy blocked". */
    var muteChunksThreshold: Int = 24 // ~3 s at 128 ms chunks

    /** True while the pipeline must ignore triggers (post-save cooldown). */
    val isCoolingDown: Boolean get() = clock() < cooldownUntilNs

    /**
     * Process exactly one audio chunk. Returns false when the source errored or is exhausted.
     */
    fun processOneChunk(): Boolean {
        applyPendingUpdates()
        advanceNoteCaptureDeadline()

        val n = audio.read(buffer, 0, chunkSize)
        if (n <= 0) {
            // Source ended or failed: flush an in-flight note so it is never silently dropped.
            fireNoteCaptureIfAny()
            if (audio.isExhausted()) return false
            onSourceError?.invoke("AudioRecord read returned $n")
            return false
        }

        trackMute(n)
        val rms = computeRms(n)

        val suppressed = isSuppressed()
        if (suppressed) {
            // While our own cue plays, drain real audio but feed silence so the app never
            // transcribes itself. The gate is closed during the window; real speech reopens it.
            voiceGate.forceClose()
            lastGateOpen = false
            spotter.acceptWaveForm(zeros, 0, n)
            return true
        }

        val gateOpen = voiceGate.process(rms)
        if (lastGateOpen && !gateOpen) {
            // The gate's tail elapsed after speech: a natural end of utterance. A dictation
            // capture that already has note words is complete.
            if (noteCapture?.hasNoteWords == true) fireNoteCaptureIfAny()
        }
        lastGateOpen = gateOpen

        if (!gateOpen) {
            // Sustained silence: skip the expensive recognizer work for this chunk. The Vosk
            // stream simply does not advance; the next active chunk resumes exactly where the
            // stream left off, so matching across the pause still works.
            return true
        }

        val t0 = clock()
        val events = spotter.acceptWaveForm(buffer, 0, n)
        val elapsedMs = (clock() - t0) / 1_000_000f
        onChunkProcessed?.invoke(elapsedMs, rms)

        if (events.isNotEmpty()) {
            handleFinalWords(events)
        } else if (triggerTokens.isNotEmpty()) {
            onPartial?.invoke(spotter.partialResult())
        }
        return true
    }

    private fun handleFinalWords(events: List<RecognitionEvent>) {
        val wallNs = clock()

        val capture = noteCapture
        if (capture != null) {
            // Dictation capture in progress: every final word is note material — unless it
            // contains a second trigger, which terminates the capture at that point.
            events.forEach { capture.allWords.add(it) }
            if (capture.allWords.size >= maxNoteWords) {
                fireNoteCaptureIfAny()
                return
            }
            val words = capture.allWords.map { it.event.word }
            // Only the note region can contain a *second* trigger; the prefix before the first
            // trigger is frozen history and may legitimately contain another phrase.
            val second = TriggerMatcher.findFirstMatch(
                words.subList(capture.noteWordStart, words.size),
                triggerTokens,
            )
            if (second != null) {
                // Keep only the words before the second trigger as the note; the cooldown makes
                // an immediate second save unsafe (GPS acquisition takes seconds).
                capture.truncateToNoteWordCount(second.wordStart)
                fireNoteCaptureIfAny()
            }
            return
        }

        events.forEach { recentWords.addLast(RecentWord(it, wallNs)) }
        // Expire by wall clock. With the voice gate stalling the Vosk stream during silence,
        // stream-time expiry could pair a half-spoken word with another utterance minutes
        // later; wall-clock expiry keeps the split-phrase window honestly ~4 s wide.
        while (recentWords.isNotEmpty() &&
            (wallNs - recentWords.first().wallNanos) > maxRecentSeconds * 1_000_000_000L
        ) {
            recentWords.removeFirst()
        }
        if (recentWords.isEmpty()) return

        val words = recentWords.map { it.event.word }
        val match = TriggerMatcher.findFirstMatch(words, triggerTokens) ?: return

        // Score the exact words that matched, at the offsets that matched.
        val confidence = meanConfidenceAt(match.wordStart, match.wordCount)
        if (confidence < minConfidence) return

        val nowNs = clock()
        if (nowNs < cooldownUntilNs) {
            // Still cooling down: swallow the duplicate but consume the words.
            recentWords.clear()
            return
        }

        val phrase = triggerPhrases.getOrNull(match.triggerIndex) ?: return
        if (dictation) {
            // Keep the whole matched window (prefix + trigger + what followed in the same final
            // segment) for the transcript, then keep collecting the dictated note.
            val windowWords = ArrayDeque(recentWords.map { it.event })
            recentWords.clear()
            noteCapture = NoteCapture(
                phrase = phrase,
                confidence = confidence,
                startedNs = nowNs,
                allWords = windowWords,
                noteWordStart = match.wordStart + match.wordCount,
            )
            return
        }

        cooldownUntilNs = nowNs + cooldownSeconds * 1_000_000_000L
        recentWords.clear()
        onTrigger?.invoke(nowNs, TriggerMatcher.normalize(phrase), confidence, null, null)
    }

    /** End a dictation capture when its wall-clock window expires. Audio thread only. */
    private fun advanceNoteCaptureDeadline() {
        val capture = noteCapture ?: return
        if (clock() - capture.startedNs > noteWindowSeconds * 1_000_000_000L) {
            fireNoteCaptureIfAny()
        }
    }

    private fun fireNoteCaptureIfAny() {
        val capture = noteCapture ?: return
        noteCapture = null
        val all = capture.allWords.map { it.event.word }
        val transcript = all.joinToString(" ").trim().ifEmpty { null }
        val noteWords = if (capture.noteWordStart >= all.size) {
            emptyList()
        } else {
            all.subList(capture.noteWordStart, all.size)
        }
        val note = noteWords.joinToString(" ").trim().ifEmpty { null }
        val nowNs = clock()
        cooldownUntilNs = nowNs + cooldownSeconds * 1_000_000_000L
        recentWords.clear()
        onTrigger?.invoke(nowNs, TriggerMatcher.normalize(capture.phrase), capture.confidence, note, transcript)
    }

    /** Request a live trigger-phrase change; applied safely on the audio thread. */
    fun updateTriggers(newPhrases: List<String>) {
        pendingTriggers = newPhrases
    }

    /** Request a dictation-mode change; applied safely on the audio thread. */
    fun updateDictation(enabled: Boolean) {
        pendingDictation = enabled
    }

    /** Must only be called from the audio thread (inside [processOneChunk]). */
    private fun applyPendingUpdates() {
        pendingTriggers?.let { phrases ->
            pendingTriggers = null
            triggerPhrases = phrases
            triggerTokens = phrases.map(GrammarJson::wordsOf)
            recentWords.clear()
        }
        pendingDictation?.let { enabled ->
            pendingDictation = null
            if (enabled != dictation) {
                dictation = enabled
                noteCapture = null
                recentWords.clear()
            }
        }
    }

    private fun meanConfidenceAt(start: Int, count: Int): Float {
        if (count <= 0 || start < 0 || start + count > recentWords.size) return 0f
        var sum = 0.0
        for (index in start until start + count) {
            sum += recentWords.elementAt(index).event.confidence.toDouble()
        }
        return (sum / count).toFloat()
    }

    /**
     * A dictation capture in progress, created when a trigger matches in dictation mode.
     * [allWords] holds every recognised word from the match window onward (prefix words
     * included, so the transcript is complete); the note is the words from [noteWordStart] on.
     */
    private class NoteCapture(
        val phrase: String,
        val confidence: Float,
        val startedNs: Long,
        val allWords: ArrayDeque<RecognitionEvent>,
        /** Index of the first post-trigger word inside [allWords]. */
        val noteWordStart: Int,
    ) {
        val hasNoteWords: Boolean get() = noteWordStart < allWords.size

        /** Drop everything from note word [count] onward (second-trigger termination). */
        fun truncateToNoteWordCount(count: Int) {
            val last = noteWordStart + count.coerceAtLeast(0)
            while (allWords.size > last) allWords.removeLast()
        }
    }

    /** A recognised word plus the wall-clock time it was captured. */
    private data class RecentWord(val event: RecognitionEvent, val wallNanos: Long)

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
        fireNoteCaptureIfAny()
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
