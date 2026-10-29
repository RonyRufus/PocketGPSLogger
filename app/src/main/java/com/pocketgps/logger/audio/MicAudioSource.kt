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
    @Synchronized
    fun ensureInitialized() {
        if (record?.state == AudioRecord.STATE_INITIALIZED) return
        // Discard a stale/uninitialized recorder before attempting a fresh allocation.
        releaseRecord()
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            throw MicException("AudioRecord.getMinBufferSize returned $minBuf")
        }
        val bufferBytes = maxOf(minBuf * 2, chunkSize * 4)
        val audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )
        } catch (t: RuntimeException) {
            throw MicException("AudioRecord construction failed: ${t.message ?: t.javaClass.simpleName}")
        }
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { audioRecord.release() }
            throw MicException("AudioRecord failed to initialize (permission or hardware busy)")
        }
        record = audioRecord
    }

    @Synchronized
    override fun start() {
        ensureInitialized()
        val r = record ?: throw MicException("not initialized")
        if (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) return
        try {
            r.startRecording()
            if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw MicException("AudioRecord did not enter RECORDSTATE_RECORDING")
            }
        } catch (t: RuntimeException) {
            // If startRecording fails, release the native audio resource here. The service assigns
            // this source only after start() returns, so its normal cleanup cannot see this object.
            releaseRecord()
            if (t is MicException) throw t
            throw MicException("AudioRecord start failed: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    override fun read(dest: ShortArray, offset: Int, length: Int): Int {
        val r = record ?: return -1
        return try {
            val n = r.read(dest, offset, length)
            if (n >= 0) n else n
        } catch (t: IllegalStateException) {
            -1
        }
    }

    @Synchronized
    override fun stop() {
        releaseRecord()
    }

    @Synchronized
    private fun releaseRecord() {
        val r = record
        record = null
        if (r != null) {
            runCatching {
                if (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) r.stop()
            }
            runCatching { r.release() }
        }
    }

    class MicException(message: String) : IllegalStateException(message)
}
