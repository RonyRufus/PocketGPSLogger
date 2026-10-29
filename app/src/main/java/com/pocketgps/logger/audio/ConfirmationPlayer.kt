package com.pocketgps.logger.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.pocketgps.logger.settings.ConfirmationMode
import java.util.Locale
import java.util.UUID

/**
 * Plays the short success/failure confirmation through the active audio route (wired or
 * Bluetooth earphone when connected).
 *
 * - Speech uses [TextToSpeech] with navigation-guidance attributes so it routes to the headset
 *   and ducks music rather than blasting the speaker.
 * - Tone mode uses short [ToneGenerator] beeps for latency-sensitive use.
 * - Silent mode plays nothing.
 *
 * [isPlaying] is consulted by the recognition pipeline to suppress the microphone while the cue
 * plays, so the app never transcribes its own confirmation.
 */
class ConfirmationPlayer(private val context: Context) {

    private var mode: ConfirmationMode = ConfirmationMode.SPEECH

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingText: String? = null

    private var toneGenerator: ToneGenerator? = null
    private var suppressUntilNs = 0L

    /** When true, cues are muted (e.g. earphone disconnected mid-session). */
    @Volatile
    private var silenced = false

    fun setSilenced(value: Boolean) {
        silenced = value
    }

    /** Prepare TTS eagerly so the first confirmation is not delayed. Safe to call repeatedly. */
    fun initialize() {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale.ENGLISH
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) = Unit
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) = Unit
                })
            }
            pendingText?.let { text ->
                pendingText = null
                if (ttsReady) speakInternal(text)
            }
        }
    }

    fun setMode(newMode: ConfirmationMode) {
        mode = newMode
        if (newMode == ConfirmationMode.SPEECH) initialize()
    }

    /** Short, deliberately quiet acknowledgement. */
    fun playSuccess() = playCue(success = true)

    fun playFailure() = playCue(success = false)

    private fun playCue(success: Boolean) {
        if (silenced) return
        when (mode) {
            ConfirmationMode.SILENT -> Unit
            ConfirmationMode.TONE -> playTone(success)
            ConfirmationMode.SPEECH -> {
                initialize()
                speakInternal(if (success) "Location saved" else "Location unavailable")
            }
        }
    }

    /** Speak an arbitrary short string (used by the setup test screen). */
    fun speak(text: String) {
        initialize()
        speakInternal(text)
    }

    private fun speakInternal(text: String) {
        if (!ttsReady) {
            pendingText = text
            return
        }
        val id = UUID.randomUUID().toString()
        // QUEUE_FLUSH: a new confirmation supersedes an older one; never build a backlog.
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        suppressUntilNs = SystemClock.elapsedRealtimeNanos() + 2_000_000_000L
    }

    private fun playTone(success: Boolean) {
        val generator = toneGenerator ?: runCatching {
            ToneGenerator(AudioManager.STREAM_MUSIC, TONE_VOLUME).also { toneGenerator = it }
        }.getOrNull() ?: return
        val tone = if (success) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_NACK
        runCatching { generator.startTone(tone, TONE_DURATION_MS) }
        // Reserve a suppression window slightly longer than the tone itself.
        suppressUntilNs = SystemClock.elapsedRealtimeNanos() + (TONE_DURATION_MS + 250L) * 1_000_000L
    }

    /** True while a cue is playing (or protected by its suppression window). */
    fun isPlaying(): Boolean {
        if (SystemClock.elapsedRealtimeNanos() < suppressUntilNs) return true
        return runCatching { tts?.isSpeaking == true }.getOrDefault(false)
    }

    fun shutdown() {
        runCatching { tts?.stop(); tts?.shutdown() }
        tts = null
        ttsReady = false
        runCatching { toneGenerator?.release() }
        toneGenerator = null
        suppressUntilNs = 0L
    }

    companion object {
        private const val TONE_VOLUME = 45 // quiet; comfortable through an earphone
        private const val TONE_DURATION_MS = 120
    }
}
