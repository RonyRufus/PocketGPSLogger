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
 * Dictation mode (voice notes) — trigger-first:
 *  - The trigger is ALWAYS spotted by the constrained-grammar recogniser, so notes ON and OFF
 *    detect the trigger identically. Notes never switch the spotter to free transcription.
 *  - Every audio chunk fed to the spotter is also kept in a bounded ring buffer. On a trigger
 *    match the pipeline opens a note capture: audio from the end of the trigger onward (taken
 *    from the ring, so words spoken in the same breath are kept) is fed to a separate
 *    unconstrained note transcriber, a few chunks per loop iteration so the audio loop never
 *    stalls. The capture ends after ~1 s of silence following speech, after a short grace period
 *    when nothing follows the trigger (location-only save), or at a hard time cap.
 *  - The note transcriber is flushed with finalResult(), so no trailing silence is needed to
 *    emit the last words. Transcription failure degrades to a location-only save.
 *  - With dictation disabled the trigger fires immediately with a null note.
 */
class RecognitionPipeline(
    private val audio: PcmAudioSource,
    private val spotter: KeywordSpotter,
    initialTriggers: List<String>,
    private val chunkSize: Int = 2048,
    /** Monotonic nanoseconds provider (SystemClock.elapsedRealtimeNanos in production). */
    private val clock: () -> Long,
    cooldownSeconds: Int = 3,
    /** Hard cap (wall-clock seconds) on one note capture. */
    private val noteWindowSeconds: Float = 10f,
    /** Seconds to wait for speech after a trigger before saving location-only. */
    private val noteGraceSeconds: Float = 2f,
    /** Silence (after note speech) that ends the capture. */
    private val noteEndSilenceSeconds: Float = 1f,
    /** Seconds of fed audio retained for the post-trigger note (bounds memory: ~16 KB/s). */
    private val ringSeconds: Int = 8,
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

    // ---- bounded ring of exactly the audio fed to the spotter (indexed by fed-sample count)
    private val sr = audio.sampleRate
    private val ringSamples = sr * ringSeconds
    private val ring = ShortArray(ringSamples)
    private var ringTotal = 0L
    private val noteBuf = ShortArray(chunkSize)
    private val endSilenceChunks = Math.ceil(noteEndSilenceSeconds * sr / chunkSize.toDouble()).toInt().coerceAtLeast(1)

    /** In-flight note capture after a trigger matched; null while just spotting. */
    private var noteCapture: NoteCapture? = null

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
        applyPendingUpdates()

        val n = audio.read(buffer, 0, chunkSize)
        if (n <= 0) {
            // Source ended or failed: flush an in-flight note so the location is never dropped.
            completeCapture(drain = false)
            if (audio.isExhausted()) return false
            onSourceError?.invoke("AudioRecord read returned $n")
            return false
        }

        trackMute(n)
        val rms = computeRms(n)
        noteCapture?.observe(rms)

        if (isSuppressed()) {
            // While our own cue plays, drain real audio but feed silence so the app never
            // transcribes itself. The gate is closed during the window; real speech reopens it.
            voiceGate.forceClose()
            feedSpotter(zeros, n)
            serviceCapture()
            return true
        }

        if (!voiceGate.process(rms)) {
            // Sustained silence: skip the expensive recognizer work for this chunk. The stream
            // simply does not advance; the next active chunk resumes where it left off.
            serviceCapture()
            return true
        }

        val t0 = clock()
        val events = feedSpotter(buffer, n)
        val elapsedMs = (clock() - t0) / 1_000_000f
        onChunkProcessed?.invoke(elapsedMs, rms)

        if (noteCapture != null) {
            // A capture is open: trigger spotting is done for this utterance. Further spotter
            // output is ignored (the cooldown makes an immediate second save unsafe anyway).
        } else if (events.isNotEmpty()) {
            handleFinalWords(events)
        } else if (triggerTokens.isNotEmpty()) {
            onPartial?.invoke(spotter.partialResult())
        }
        serviceCapture()
        return true
    }

    /** Writes the chunk to the ring in lock-step with the spotter so sample indices agree. */
    private fun feedSpotter(pcm: ShortArray, n: Int): List<RecognitionEvent> {
        val pos = (ringTotal % ringSamples).toInt()
        val first = minOf(n, ringSamples - pos)
        System.arraycopy(pcm, 0, ring, pos, first)
        if (first < n) System.arraycopy(pcm, first, ring, 0, n - first)
        ringTotal += n
        return spotter.acceptWaveForm(pcm, 0, n)
    }

    private fun ringRead(startAbs: Long, len: Int, dest: ShortArray) {
        val pos = (startAbs % ringSamples).toInt()
        val first = minOf(len, ringSamples - pos)
        System.arraycopy(ring, pos, dest, 0, first)
        if (first < len) System.arraycopy(ring, 0, dest, first, len - first)
    }

    private fun handleFinalWords(events: List<RecognitionEvent>) {
        val wallNs = clock()
        events.forEach { recentWords.addLast(RecentWord(it, wallNs)) }
        // Expire by wall clock. With the voice gate stalling the stream during silence,
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
        if (dictation && spotter.beginNote()) {
            startCapture(phrase, confidence, nowNs, match)
            return
        }

        // Notes off (or transcriber unavailable): location-only save, immediately.
        cooldownUntilNs = nowNs + cooldownSeconds * 1_000_000_000L
        recentWords.clear()
        onTrigger?.invoke(nowNs, TriggerMatcher.normalize(phrase), confidence, null, null)
    }

    private fun startCapture(phrase: String, confidence: Float, nowNs: Long, match: TriggerMatcher.MultiMatch) {
        val triggerEnd = recentWords.elementAt(match.wordStart + match.wordCount - 1).event
        val prefix = recentWords.take(match.wordStart).map { it.event.word }
        recentWords.clear()

        val ringStart = maxOf(0L, ringTotal - ringSamples)
        val rawEnd = (triggerEnd.endStreamSeconds * sr).toLong()
        // Unusable timestamps (missing, or outside the retained audio): transcribe only what is
        // spoken from now on rather than risk feeding the trigger words into the note.
        val valid = triggerEnd.endStreamSeconds > 0f && rawEnd in ringStart..ringTotal
        val start = if (valid) minOf(rawEnd + (TRIGGER_END_MARGIN_S * sr).toLong(), ringTotal) else ringTotal
        val agoNs = (((ringTotal - start) * 1_000_000_000L) / sr).coerceAtLeast(0L)

        val cap = NoteCapture(
            phrase = phrase,
            confidence = confidence,
            triggerNs = nowNs - agoNs,
            startedNs = nowNs,
            prefixWords = prefix,
            cursor = start,
        )
        // Classify audio already spoken after the trigger (same-breath note, trailing silence).
        var p = start
        while (p < ringTotal) {
            val len = minOf(chunkSize.toLong(), ringTotal - p).toInt()
            ringRead(p, len, noteBuf)
            cap.observe(rmsOf(noteBuf, len))
            p += len
        }
        noteCapture = cap
    }

    /** Per-iteration capture work: bounded catch-up feeding, end detection, completion. */
    private fun serviceCapture() {
        val cap = noteCapture ?: return
        pumpNote(cap, if (cap.finishing) 3 * chunkSize else 2 * chunkSize)
        if (!cap.finishing) {
            val elapsed = clock() - cap.startedNs
            val done = (cap.voiced && cap.quietRun >= endSilenceChunks) ||
                (!cap.voiced && elapsed > noteGraceSeconds * 1_000_000_000L) ||
                elapsed > noteWindowSeconds * 1_000_000_000L
            if (done) cap.finishing = true
        }
        if (cap.finishing && cap.cursor >= ringTotal) completeCapture(drain = false)
    }

    /** Feed up to [budget] backlog samples from the ring to the note transcriber. */
    private fun pumpNote(cap: NoteCapture, budget: Int) {
        var left = budget
        val oldest = maxOf(0L, ringTotal - ringSamples)
        if (cap.cursor < oldest) cap.cursor = oldest // fell behind the ring: bounded loss
        while (left > 0 && cap.cursor < ringTotal) {
            val len = minOf(minOf(left.toLong(), chunkSize.toLong()), ringTotal - cap.cursor).toInt()
            ringRead(cap.cursor, len, noteBuf)
            spotter.acceptNoteAudio(noteBuf, 0, len)
            cap.cursor += len
            left -= len
        }
    }

    /**
     * Finish the capture and fire exactly once. [drain] feeds the whole remaining backlog first
     * (only worthwhile when time allows); otherwise whatever was transcribed so far is used.
     */
    private fun completeCapture(drain: Boolean) {
        val cap = noteCapture ?: return
        noteCapture = null
        if (drain) pumpNote(cap, Int.MAX_VALUE)
        // No speech was ever detected after the trigger: whatever the transcriber produced from
        // silence/noise is not a note.
        val note = if (dictation && cap.voiced) cleanNote(spotter.finishNote(), cap.phrase) else null
        if (note == null) spotter.cancelNote()
        fire(cap, note)
    }

    private fun fire(cap: NoteCapture, note: String?) {
        cooldownUntilNs = clock() + cooldownSeconds * 1_000_000_000L
        recentWords.clear()
        val phraseText = TriggerMatcher.normalize(cap.phrase)
        val transcript = (cap.prefixWords + phraseText + listOfNotNull(note)).joinToString(" ").trim()
        onTrigger?.invoke(cap.triggerNs, phraseText, cap.confidence, note, transcript.ifEmpty { null })
    }

    /** Normalise transcriber words; drop [unk]; drop a leading echo of the trigger; cap length. */
    private fun cleanNote(events: List<RecognitionEvent>, phrase: String): String? {
        var tokens = events.flatMap { TriggerMatcher.tokens(it.word) }.filter { it != "unk" }
        val trig = TriggerMatcher.tokens(phrase)
        if (trig.isNotEmpty() && tokens.size >= trig.size && tokens.subList(0, trig.size) == trig) {
            tokens = tokens.drop(trig.size)
        }
        return tokens.take(maxNoteWords).joinToString(" ").ifEmpty { null }
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
                // Turning notes off mid-capture must still save the location: finish now, no note.
                // Nothing else is reset — the trigger recogniser and ring are untouched.
                if (!enabled) completeCapture(drain = false)
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

    /** An open post-trigger note capture. Audio thread only. */
    private inner class NoteCapture(
        val phrase: String,
        val confidence: Float,
        /** Monotonic time of the end of the trigger speech (for the saved timestamp). */
        val triggerNs: Long,
        /** Monotonic time the trigger was recognised (capture timeouts run from here). */
        val startedNs: Long,
        val prefixWords: List<String>,
        /** Absolute fed-sample index of the next audio to give the note transcriber. */
        var cursor: Long,
    ) {
        var voiced = false
        var quietRun = 0
        var finishing = false

        fun observe(rms: Float) {
            when {
                rms >= voiceGate.openRms -> { voiced = true; quietRun = 0 }
                rms >= voiceGate.closeRms -> quietRun = 0
                else -> quietRun++
            }
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
        completeCapture(drain = false)
    }

    private fun rmsOf(pcm: ShortArray, n: Int): Float {
        if (n == 0) return 0f
        var sum = 0.0
        for (i in 0 until n) { val v = pcm[i].toDouble(); sum += v * v }
        return (Math.sqrt(sum / n) / Short.MAX_VALUE).toFloat()
    }

    private companion object {
        /** Skip the tail of the last trigger word so its decay is not transcribed as a note. */
        const val TRIGGER_END_MARGIN_S = 0.04f
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
