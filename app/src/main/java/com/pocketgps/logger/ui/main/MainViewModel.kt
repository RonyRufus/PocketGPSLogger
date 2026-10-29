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
import com.pocketgps.logger.location.LocationAcquirer
import com.pocketgps.logger.model.ModelManager
import com.pocketgps.logger.settings.AppSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Backs the Main screen. Observes the service-owned engine (or a disabled default when the
 * service is not running), the persisted settings, and the latest saved record.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ServiceLocator.locationRepository
    private val settingsRepository = ServiceLocator.settingsRepository
    private val modelManager = ServiceLocator.modelManager
    private val locationAcquirer = LocationAcquirer(application)

    val engineState: StateFlow<ListenerUiState> = EngineProvider.engine
        .flatMapLatest { engine -> engine?.uiState ?: flowOf(ListenerUiState()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListenerUiState())

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val latest: StateFlow<LocationRecord?> = repository.observeLatest()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val savedCount: StateFlow<Int> = repository.observeAll()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _modelStatus = MutableStateFlow<ModelManager.Status>(ModelManager.Status.Missing)
    val modelStatus: StateFlow<ModelManager.Status> = _modelStatus

    fun refresh() {
        _modelStatus.value = modelManager.status()
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
