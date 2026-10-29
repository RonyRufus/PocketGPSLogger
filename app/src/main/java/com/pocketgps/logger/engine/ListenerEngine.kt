package com.pocketgps.logger.engine

import com.pocketgps.logger.data.LocationRecord
import com.pocketgps.logger.location.LocationAcquirer
import com.pocketgps.logger.location.LocationRepository
import com.pocketgps.logger.audio.ConfirmationPlayer
import com.pocketgps.logger.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the listening state machine and orchestrates trigger → GPS → persist → confirm.
 *
 * Threading:
 *  - [onTriggerDetected] is called from the audio thread and only flips a flag / launches work.
 *  - All GPS and Room work happens in [scope] on [Dispatchers.IO]; the audio loop is never blocked.
 *  - The UI observes [uiState]; the database is the single source of truth for saved records.
 */
class ListenerEngine(
    private val repository: LocationRepository,
    private val locationAcquirer: LocationAcquirer,
    private val confirmation: ConfirmationPlayer,
    private val scope: CoroutineScope,
) {

    private val _uiState = MutableStateFlow(ListenerUiState())
    val uiState: StateFlow<ListenerUiState> = _uiState.asStateFlow()

    @Volatile private var settings: AppSettings = AppSettings()

    /** Guards against overlapping captures for rapid or duplicated triggers. */
    @Volatile private var captureInProgress = false

    /** Set false when listening stops, so in-flight captures do not resurrect the UI state. */
    @Volatile private var active = false

    private var dbObserver: Job? = null

    /** Transcript can finish before or after GPS acquisition; key it by the trigger timestamp. */
    private val pendingTranscripts = ConcurrentHashMap<Long, String>()

    // ------------------------------------------------------------------ lifecycle

    /** Begin observing persisted records so counts/last-saved survive app restarts. */
    fun startObservingDatabase() {
        if (dbObserver != null) return
        dbObserver = scope.launch {
            repository.observeLatest().collect { latest ->
                _uiState.update { it.copy(lastSaved = latest) }
            }
        }
        scope.launch {
            repository.observeAll().collect { all ->
                _uiState.update { it.copy(savedCount = all.size) }
            }
        }
    }

    fun updateSettings(newSettings: AppSettings) {
        settings = newSettings
        confirmation.setMode(newSettings.confirmationMode)
        _uiState.update { it.copy(triggerPhrase = newSettings.triggerPhrase) }
    }

    fun updateReadiness(gpsReady: Boolean, microphoneReady: Boolean) {
        _uiState.update { it.copy(gpsReady = gpsReady, microphoneReady = microphoneReady) }
    }

    fun updateAudioRoute(headsetConnected: Boolean, inputRoute: String?) {
        _uiState.update {
            it.copy(headsetConnected = headsetConnected, inputRoute = inputRoute)
        }
    }

    fun markInitializing() {
        active = true
        _uiState.update {
            it.copy(
                state = ListenerState.INITIALIZING,
                statusMessage = "Starting…",
                errorMessage = null,
                lastEventAtMs = System.currentTimeMillis(),
            )
        }
    }

    fun markListening() {
        if (!active) return
        _uiState.update {
            it.copy(
                state = ListenerState.LISTENING,
                statusMessage = "Listening for \"${settings.triggerPhrase}\"",
                errorMessage = null,
                lastEventAtMs = System.currentTimeMillis(),
            )
        }
    }

    fun onAudioInterrupted(reason: String) {
        if (!active) return
        _uiState.update {
            it.copy(
                state = ListenerState.RECOVERING,
                statusMessage = "Audio interrupted: $reason",
                lastEventAtMs = System.currentTimeMillis(),
            )
        }
    }

    fun onAudioRecovered() {
        if (!active) return
        if (_uiState.value.state == ListenerState.RECOVERING) {
            markListening()
        }
    }

    fun onError(message: String) {
        active = false
        captureInProgress = false
        _uiState.update {
            it.copy(
                state = ListenerState.ERROR,
                statusMessage = null,
                errorMessage = message,
                lastEventAtMs = System.currentTimeMillis(),
            )
        }
    }

    fun markDisabled() {
        active = false
        captureInProgress = false
        _uiState.update {
            it.copy(
                state = ListenerState.DISABLED,
                statusMessage = "Listening stopped",
                errorMessage = null,
                lastEventAtMs = System.currentTimeMillis(),
            )
        }
    }

    // ------------------------------------------------------------------ trigger flow

    /**
     * Called from the audio thread the moment the full trigger phrase is accepted.
     * [triggerTimestampMs] must be the wall-clock time of detection (not of the DB write).
     */
    fun onTriggerDetected(triggerTimestampMs: Long) {
        if (!active) return
        if (captureInProgress) return // one utterance has already started a capture
        captureInProgress = true

        val phrase = settings.triggerPhrase
        _uiState.update {
            it.copy(
                state = ListenerState.CAPTURING,
                statusMessage = "Getting location…",
                lastEventAtMs = triggerTimestampMs,
            )
        }

        scope.launch {
            try {
                val result = locationAcquirer.acquire(triggerTimestampMs, settings)
                when (result) {
                    is LocationAcquirer.Result.Success -> persist(triggerTimestampMs, phrase, result.fix)
                    is LocationAcquirer.Result.Unavailable -> {
                        confirmation.playFailure()
                        if (active) {
                            _uiState.update {
                                it.copy(
                                    state = ListenerState.LISTENING,
                                    statusMessage = "Location unavailable: ${result.reason}",
                                    lastEventAtMs = System.currentTimeMillis(),
                                )
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                runCatching { confirmation.playFailure() }
                if (active) {
                    _uiState.update {
                        it.copy(
                            state = ListenerState.LISTENING,
                            statusMessage = "Location unavailable: ${t.message ?: "unknown error"}",
                            lastEventAtMs = System.currentTimeMillis(),
                        )
                    }
                }
            } finally {
                captureInProgress = false
            }
        }
    }

    /**
     * Called by the audio pipeline after offline transcription ends. Handles both orderings:
     * the note may arrive before the GPS row is inserted, or after it.
     */
    fun onTranscriptCaptured(triggerTimestampMs: Long, transcript: String) {
        val note = transcript.trim().take(2_000)
        if (triggerTimestampMs <= 0L || note.isEmpty()) return
        pendingTranscripts[triggerTimestampMs] = note
        scope.launch(Dispatchers.IO) {
            val updated = runCatching {
                repository.updateNotesForTrigger(triggerTimestampMs, note)
            }.getOrDefault(0)
            if (updated > 0) pendingTranscripts.remove(triggerTimestampMs, note)
        }
    }

    private suspend fun persist(
        triggerTimestampMs: Long,
        triggerPhrase: String,
        fix: LocationAcquirer.Fix,
    ) {
        if (!active) return
        _uiState.update { it.copy(state = ListenerState.SAVING) }

        val warning = buildWarning(fix)
        val voiceNote = pendingTranscripts[triggerTimestampMs]
        val record = LocationRecord(
            latitude = fix.latitude,
            longitude = fix.longitude,
            triggerTimestamp = triggerTimestampMs,
            locationTimestamp = fix.fixTimestampMs,
            accuracyMeters = fix.accuracyMeters,
            locationSource = fix.source.wire,
            locationAgeSeconds = fix.ageSeconds,
            triggerPhrase = triggerPhrase,
            saveStatus = "success",
            notes = combineNotes(warning, voiceNote),
        )

        val committed = withContext(Dispatchers.IO) {
            runCatching { repository.insert(record) }.getOrNull()
        }

        if (committed != null) {
            // Close the race where transcription arrived while the insert was in flight.
            pendingTranscripts[triggerTimestampMs]?.let { transcript ->
                runCatching {
                    repository.updateNotesForTrigger(
                        triggerTimestampMs,
                        combineNotes(warning, transcript) ?: transcript,
                    )
                }
                pendingTranscripts.remove(triggerTimestampMs, transcript)
            }
            val saved = record.copy(id = committed, notes = combineNotes(warning, voiceNote))
            if (active) {
                _uiState.update {
                    it.copy(
                        state = ListenerState.LISTENING,
                        lastSaved = saved,
                        statusMessage = "Location saved",
                        lastEventAtMs = System.currentTimeMillis(),
                    )
                }
            }
            // Persist first, then confirm. Audio is never the only indication of success.
            confirmation.playSuccess()
        } else {
            // The DB did not confirm the write: never claim success.
            runCatching { confirmation.playFailure() }
            if (active) {
                _uiState.update {
                    it.copy(
                        state = ListenerState.LISTENING,
                        statusMessage = "Save failed — record not committed",
                        lastEventAtMs = System.currentTimeMillis(),
                    )
                }
            }
        }
    }

    private fun combineNotes(warning: String?, transcript: String?): String? = when {
        warning.isNullOrBlank() -> transcript?.takeIf { it.isNotBlank() }
        transcript.isNullOrBlank() -> warning
        else -> "$warning\n$transcript"
    }

    private fun buildWarning(fix: LocationAcquirer.Fix): String? {
        val parts = mutableListOf<String>()
        if (fix.source == com.pocketgps.logger.location.LocationSource.FALLBACK) {
            val age = fix.ageSeconds ?: 0
            parts += "Stale fix used after GPS timeout (age ${age}s)"
        }
        val accuracy = fix.accuracyMeters
        if (accuracy != null && accuracy > settings.maxAccuracyMeters) {
            parts += "Poor GPS accuracy (${accuracy.toInt()} m)"
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    fun shutdown() {
        active = false
        captureInProgress = false
        dbObserver?.cancel()
        dbObserver = null
    }
}
