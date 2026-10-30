package com.pocketgps.logger.ui.main

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pocketgps.logger.ServiceLocator
import com.pocketgps.logger.data.LocationRecord
import com.pocketgps.logger.engine.SavePipeline
import com.pocketgps.logger.engine.ServiceBus
import com.pocketgps.logger.location.LocationAcquirer
import com.pocketgps.logger.service.TapLogService
import com.pocketgps.logger.settings.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Backs the Main screen. Observes the tap-logging service via [ServiceBus] (no binding), the
 * persisted settings, and the latest saved record. The big Save button and Undo use the shared
 * [SavePipeline], so they behave exactly like pocket taps and notification actions.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ServiceLocator.locationRepository
    private val settingsRepository = ServiceLocator.settingsRepository
    private val pipeline = ServiceLocator.savePipeline
    private val acquirer = LocationAcquirer(application)

    val serviceStatus: StateFlow<ServiceBus.Status> = ServiceBus.status

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val latest: StateFlow<LocationRecord?> = repository.observeLatest()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val savedCount: StateFlow<Int> = repository.observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    sealed interface SaveOp {
        data object Idle : SaveOp
        data object Working : SaveOp
        data class Done(val record: LocationRecord) : SaveOp
        data class Failed(val reason: String) : SaveOp
    }

    private val _saveOp = MutableStateFlow<SaveOp>(SaveOp.Idle)
    val saveOp: StateFlow<SaveOp> = _saveOp

    /** One-shot manual save from the big button; same pipeline as taps and the notification. */
    fun saveSpotNow() {
        if (_saveOp.value == SaveOp.Working) return
        _saveOp.value = SaveOp.Working
        viewModelScope.launch {
            val settings = settingsRepository.settings.firstOrNull() ?: AppSettings()
            when (val result = pipeline.save("button", settings)) {
                is SavePipeline.Result.Saved -> _saveOp.value = SaveOp.Done(result.record)
                is SavePipeline.Result.Unavailable -> _saveOp.value = SaveOp.Failed(result.reason)
                is SavePipeline.Result.WriteFailed -> _saveOp.value = SaveOp.Failed(result.reason)
            }
        }
    }

    fun clearSaveOp() {
        _saveOp.value = SaveOp.Idle
    }

    /** Undo the most recent save regardless of which input produced it. */
    fun undoLastSave(onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            onResult(pipeline.undoLast())
        }
    }

    fun hasLocationPermission(): Boolean =
        check(android.Manifest.permission.ACCESS_FINE_LOCATION) ||
            check(android.Manifest.permission.ACCESS_COARSE_LOCATION)

    fun hasNotificationPermission(): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
            check(android.Manifest.permission.POST_NOTIFICATIONS)

    fun isGpsReady(): Boolean =
        acquirer.hasPermission() && acquirer.isAnyProviderEnabled()

    fun startTapLogging() {
        TapLogService.start(getApplication())
    }

    fun stopTapLogging() {
        TapLogService.stop(getApplication())
    }

    private fun check(permission: String): Boolean =
        ContextCompat.checkSelfPermission(getApplication(), permission) ==
            PackageManager.PERMISSION_GRANTED
}
