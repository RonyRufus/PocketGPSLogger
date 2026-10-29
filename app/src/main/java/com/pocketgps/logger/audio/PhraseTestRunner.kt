package com.pocketgps.logger.audio

import android.os.SystemClock
import com.pocketgps.logger.model.ModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One-shot microphone + recognizer test used by the Setup screen.
 *
 * This is deliberately separate from the foreground service: it runs only while the app is
 * visible, uses its own short-lived AudioRecord and Recognizer, and always releases them.
 */
class PhraseTestRunner(
    private val modelManager: ModelManager,
) {

    data class Result(
        val matched: Boolean,
        val transcript: String,
        val message: String,
    )

    /**
     * Listen for up to [durationMs] and report whether [triggerPhrase] was heard.
     * [onPartial] receives live partial text for the on-screen meter.
     */
    suspend fun run(
        triggerPhrase: String,
        durationMs: Long = 8_000L,
        onPartial: (String) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        val model = modelManager.loadModel()
            ?: return@withContext Result(false, "", "Offline model is not installed")

        val mic = MicAudioSource()
        var spotter: VoskKeywordSpotter? = null
        var matched = false

        try {
            mic.ensureInitialized()
            mic.start()
            val s = VoskKeywordSpotter(
                model = model,
                sampleRate = MicAudioSource.SAMPLE_RATE,
                initialPhrases = listOf(triggerPhrase),
            )
            spotter = s
            val pipeline = RecognitionPipeline(
                audio = mic,
                spotter = s,
                initialTriggerPhrase = triggerPhrase,
                clock = { SystemClock.elapsedRealtimeNanos() },
                cooldownSeconds = 1,
            )
            pipeline.onPartial = { partial -> partial?.let(onPartial) }
            pipeline.onTrigger = { _, _, _ -> matched = true }

            val deadline = SystemClock.elapsedRealtime() + durationMs
            while (!matched && SystemClock.elapsedRealtime() < deadline) {
                if (!pipeline.processOneChunk()) break
            }
        } catch (t: Throwable) {
            return@withContext Result(
                false, "",
                "Microphone test failed: ${t.message ?: t.javaClass.simpleName}",
            )
        } finally {
            runCatching { spotter?.close() }
            runCatching { mic.stop() }
        }

        if (matched) {
            Result(true, TriggerMatcher.normalize(triggerPhrase), "Phrase recognised")
        } else {
            Result(false, "", "Phrase not recognised — try again, slightly slower")
        }
    }
}
