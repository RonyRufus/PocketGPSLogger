package com.pocketgps.logger.ui.main

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pocketgps.logger.ServiceLocator
import com.pocketgps.logger.data.LocationRecord
import com.pocketgps.logger.engine.EngineProvider
import com.pocketgps.logger.engine.ListenerUiState
import com.pocketgps.logger.feedback.HapticCue
import com.pocketgps.logger.location.LocationAcquirer
import com.pocketgps.logger.model.ModelManager
import com.pocketgps.logger.settings.AppSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Backs the Main screen. Observes the service-owned engine (or a disabled default when the
 * service is not running), the persisted settings, and the latest saved record.
 *
 * The manual "Save spot now" path lives here so it works whether or not the listening
 * service is running; it uses the same acquire → insert contract as the engine.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ServiceLocator.locationRepository
    private val settingsRepository = ServiceLocator.settingsRepository
    private val modelManager = ServiceLocator.modelManager
    private val locationAcquirer = LocationAcquirer(application)
    private val haptics = HapticCue(application)

    val engineState: StateFlow<ListenerUiState> = EngineProvider.engine
        .flatMapLatest { engine -> engine?.uiState ?: flowOf(ListenerUiState()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListenerUiState())

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val latest: StateFlow<LocationRecord?> = repository.observeLatest()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val savedCount: StateFlow<Int> = repository.observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    sealed interface ManualSaveOp {
        data object Idle : ManualSaveOp
        data object Working : ManualSaveOp
        data class Done(val record: LocationRecord) : ManualSaveOp
        data class Failed(val reason: String) : ManualSaveOp
    }

    private val _manualSave = MutableStateFlow<ManualSaveOp>(ManualSaveOp.Idle)
    val manualSave: StateFlow<ManualSaveOp> = _manualSave

    private val _modelStatus = MutableStateFlow<ModelManager.Status>(ModelManager.Status.Missing)
    val modelStatus: StateFlow<ModelManager.Status> = _modelStatus

    fun refresh() {
        _modelStatus.value = modelManager.status()
    }

    /** One-shot manual save from the Main screen button; independent of the listening service. */
    fun saveSpotNow() {
        if (_manualSave.value == ManualSaveOp.Working) return
        _manualSave.value = ManualSaveOp.Working
        viewModelScope.launch {
            try {
                val settings = settingsRepository.settings.firstOrNull() ?: AppSettings()
                val triggerAt = System.currentTimeMillis()
                when (val result = locationAcquirer.acquire(triggerAt, settings)) {
                    is LocationAcquirer.Result.Success -> {
                        val record = LocationRecord(
                            latitude = result.fix.latitude,
                            longitude = result.fix.longitude,
                            triggerTimestamp = triggerAt,
                            locationTimestamp = result.fix.fixTimestampMs,
                            accuracyMeters = result.fix.accuracyMeters,
                            locationSource = result.fix.source.wire,
                            locationAgeSeconds = result.fix.ageSeconds,
                            triggerPhrase = "manual save",
                            saveStatus = "success",
                            notes = manualSaveWarning(result.fix, settings),
                        )
                        val id = withContext(Dispatchers.IO) {
                            runCatching { repository.insert(record) }.getOrNull()
                        }
                        if (id == null) {
                            if (settings.hapticFeedback) haptics.failure()
                            _manualSave.value = ManualSaveOp.Failed("Save failed — not committed")
                        } else {
                            if (settings.hapticFeedback) haptics.success()
                            _manualSave.value = ManualSaveOp.Done(record.copy(id = id))
                        }
                    }

                    is LocationAcquirer.Result.Unavailable -> {
                        if (settings.hapticFeedback) haptics.failure()
                        _manualSave.value = ManualSaveOp.Failed(result.reason)
                    }
                }
            } catch (t: Throwable) {
                _manualSave.value = ManualSaveOp.Failed(t.message ?: "Save failed")
            }
        }
    }

    fun clearManualSaveOp() {
        _manualSave.value = ManualSaveOp.Idle
    }

    private fun manualSaveWarning(fix: LocationAcquirer.Fix, settings: AppSettings): String? {
        val parts = mutableListOf<String>()
        if (fix.source == com.pocketgps.logger.location.LocationSource.FALLBACK) {
            parts += "Stale fix used after GPS timeout (age ${fix.ageSeconds ?: 0}s)"
        }
        val accuracy = fix.accuracyMeters
        if (accuracy != null && accuracy > settings.maxAccuracyMeters) {
            parts += "Poor GPS accuracy (${accuracy.toInt()} m)"
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    fun hasMicrophonePermission(): Boolean = check(
        android.Manifest.permission.RECORD_AUDIO,
    )

    fun hasLocationPermission(): Boolean =
        check(android.Manifest.permission.ACCESS_FINE_LOCATION) ||
            check(android.Manifest.permission.ACCESS_COARSE_LOCATION)

    fun hasNotificationPermission(): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
            check(android.Manifest.permission.POST_NOTIFICATIONS)

    fun isGpsReady(): Boolean =
        locationAcquirer.hasPermission() && locationAcquirer.isAnyProviderEnabled()

    fun startListening() {
        val context = getApplication<android.app.Application>()
        com.pocketgps.logger.service.HandsFreeService.start(context)
    }

    fun stopListening() {
        val context = getApplication<android.app.Application>()
        com.pocketgps.logger.service.HandsFreeService.stop(context)
    }

    fun deleteRecord(id: Long) {
        viewModelScope.launch { repository.delete(id) }
    }

    private fun check(permission: String): Boolean =
        ContextCompat.checkSelfPermission(getApplication(), permission) ==
            PackageManager.PERMISSION_GRANTED
}
