package com.pocketgps.logger.engine

import com.pocketgps.logger.data.LocationRecord
import com.pocketgps.logger.feedback.HapticCue
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

/**
 * Owns the listening state machine and orchestrates trigger → GPS → persist → confirm.
 *
 * Threading:
 *  - [onTriggerDetected] is called from the audio thread and only flips a flag / launches work.
 *  - All GPS and Room work happens in [scope] on [Dispatchers.IO]; the audio loop is never blocked.
 *  - The UI observes [uiState]; the database is the single source of truth for saved records.
 *
 * The saved-record count is observed via a COUNT(*) query rather than materialising every row;
 * with thousands of spots the UI costs the same as with ten.
 */
class ListenerEngine(
    private val repository: LocationRepository,
    private val locationAcquirer: LocationAcquirer,
    private val confirmation: ConfirmationPlayer,
    private val scope: CoroutineScope,
    private val haptics: HapticCue? = null,
) {

    private val _uiState = MutableStateFlow(ListenerUiState())
    val uiState: StateFlow<ListenerUiState> = _uiState.asStateFlow()

    @Volatile private var settings: AppSettings = AppSettings()

    /** Guards against overlapping captures for rapid or duplicated triggers. */
    @Volatile private var captureInProgress = false

    /** Set false when listening stops, so in-flight captures do not resurrect the UI state. */
    @Volatile private var active = false

    private var dbObserver: Job? = null

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
            repository.observeCount().collect { count ->
                _uiState.update { it.copy(savedCount = count) }
            }
        }
    }

    fun updateSettings(newSettings: AppSettings) {
        settings = newSettings
        confirmation.setMode(newSettings.confirmationMode)
        _uiState.update { it.copy(triggerPhrase = newSettings.primaryTrigger) }
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
                statusMessage = "Listening for \"${settings.primaryTrigger}\"",
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
    fun onTriggerDetected(
        triggerTimestampMs: Long,
        triggerPhrase: String = settings.primaryTrigger,
        observation: String? = null,
        transcript: String? = null,
    ) {
        startCapture(
            triggerTimestampMs = triggerTimestampMs,
            fromUserAction = false,
            triggerPhrase = triggerPhrase,
            observation = observation,
            transcript = transcript,
        )
    }

    /**
     * Save a spot immediately from a user action (the notification's "Save now" button).
     * Runs the same capture pipeline as a voice trigger, so persistence and feedback
     * semantics are identical.
     */
    fun saveNow() {
        if (!active) return
        startCapture(System.currentTimeMillis(), fromUserAction = true)
    }

    private fun startCapture(
        triggerTimestampMs: Long,
        fromUserAction: Boolean,
        triggerPhrase: String = settings.primaryTrigger,
        observation: String? = null,
        transcript: String? = null,
    ) {
        if (!active) return
        if (captureInProgress) return // one utterance has already started a capture
        captureInProgress = true

        val phrase = if (fromUserAction) "manual save" else triggerPhrase
        val cleanObservation = observation?.trim()?.takeIf { it.isNotEmpty() }
        val cleanTranscript = transcript?.trim()?.takeIf { it.isNotEmpty() }
        _uiState.update {
            it.copy(
                state = ListenerState.CAPTURING,
                statusMessage = if (fromUserAction) "Saving from button…" else "Getting location…",
                lastEventAtMs = triggerTimestampMs,
            )
        }

        scope.launch {
            try {
                val result = locationAcquirer.acquire(settings)
                when (result) {
                    is LocationAcquirer.Result.Success -> persist(
                        triggerTimestampMs, phrase, result.fix, cleanObservation, cleanTranscript,
                    )
                    is LocationAcquirer.Result.Unavailable -> {
                        if (cleanObservation != null) {
                            persistWithoutLocation(
                                triggerTimestampMs, phrase, cleanObservation, cleanTranscript, result.reason,
                            )
                        } else {
                            failCue()
                        }
                        if (active && cleanObservation == null) {
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
                failCue()
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

    private suspend fun persist(
        triggerTimestampMs: Long,
        triggerPhrase: String,
        fix: LocationAcquirer.Fix,
        observation: String? = null,
        transcript: String? = null,
    ) {
        if (!active) return
        _uiState.update { it.copy(state = ListenerState.SAVING) }

        val warning = buildWarning(fix)
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
            notes = warning,
            observation = observation,
            transcript = transcript,
        )

        val committed = withContext(Dispatchers.IO) {
            runCatching { repository.insert(record) }.getOrNull()
        }

        if (committed != null) {
            val saved = record.copy(id = committed)
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
            // Persist first, then confirm. Audio/haptics are never the only indication of success.
            if (settings.hapticFeedback) haptics?.success()
            confirmation.playSuccess()
        } else {
            // The DB did not confirm the write: never claim success.
            failCue()
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

    /** Save the observation even when no acceptable GPS fix was available. */
    private suspend fun persistWithoutLocation(
        triggerTimestampMs: Long,
        triggerPhrase: String,
        observation: String,
        transcript: String?,
        reason: String,
    ) {
        val record = LocationRecord(
            latitude = null,
            longitude = null,
            triggerTimestamp = triggerTimestampMs,
            locationTimestamp = null,
            accuracyMeters = null,
            locationSource = com.pocketgps.logger.location.LocationSource.NONE.wire,
            locationAgeSeconds = null,
            triggerPhrase = triggerPhrase,
            saveStatus = "success",
            notes = "GPS unavailable: $reason",
            observation = observation,
            transcript = transcript,
        )
        val id = withContext(Dispatchers.IO) {
            runCatching { repository.insert(record) }.getOrNull()
        }
        if (id != null) {
            val saved = record.copy(id = id)
            if (active) {
                _uiState.update {
                    it.copy(
                        state = ListenerState.LISTENING,
                        lastSaved = saved,
                        statusMessage = "Note saved; GPS unavailable",
                        lastEventAtMs = System.currentTimeMillis(),
                    )
                }
            }
            if (settings.hapticFeedback) haptics?.success()
            confirmation.playNoteSaved()
        } else {
            failCue()
            if (active) {
                _uiState.update {
                    it.copy(
                        state = ListenerState.LISTENING,
                        statusMessage = "Note save failed — record not committed",
                        lastEventAtMs = System.currentTimeMillis(),
                    )
                }
            }
        }
    }

    private fun failCue() {
        if (settings.hapticFeedback) haptics?.failure()
        runCatching { confirmation.playFailure() }
    }

    private fun buildWarning(fix: LocationAcquirer.Fix): String? {
        val parts = mutableListOf<String>()
        if (fix.source == com.pocketgps.logger.location.LocationSource.FALLBACK) {
            val age = fix.ageSeconds ?: 0
            parts += "Fallback fix used after GPS timeout (age ${age}s)"
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
