package com.pocketgps.logger.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * Live microphone source: `VOICE_RECOGNITION`, 16 kHz, mono, 16-bit PCM.
 *
 * The read buffer is allocated once. The pipeline reads into its own pre-allocated array so the
 * hot path performs no allocation.
 */
class MicAudioSource(
    override val sampleRate: Int = SAMPLE_RATE,
    private val chunkSize: Int = 2048,
) : PcmAudioSource {

    companion object {
        const val SAMPLE_RATE = 16_000
    }

    @SuppressLint("MissingPermission") // permission is checked by the service before start()
    private var record: AudioRecord? = null

    /** Allocates the AudioRecord. Throws [MicException] if the hardware refuses. */
    fun ensureInitialized() {
        if (record != null) return
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            throw MicException("AudioRecord.getMinBufferSize returned $minBuf")
        }
        val bufferBytes = maxOf(minBuf * 2, chunkSize * 4)
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes,
        )
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            throw MicException("AudioRecord failed to initialize (permission or hardware busy)")
        }
        record = audioRecord
    }

    override fun start() {
        ensureInitialized()
        val r = record ?: throw MicException("not initialized")
        if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            r.startRecording()
        }
    }

    override fun read(dest: ShortArray, offset: Int, length: Int): Int {
        val r = record ?: return -1
        val n = r.read(dest, offset, length)
        return if (n >= 0) n else -1
    }

    override fun stop() {
        record?.let { r ->
            runCatching {
                if (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) r.stop()
            }
            r.release()
        }
        record = null
    }

    class MicException(message: String) : IllegalStateException(message)
}
