package com.pocketgps.logger.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pocketgps.logger.ServiceLocator
import com.pocketgps.logger.location.LocationAcquirer
import com.pocketgps.logger.sensor.TapSensitivity
import com.pocketgps.logger.settings.AppSettings
import com.pocketgps.logger.settings.CachedLocationPolicy
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Backs the Settings screen: tap sensitivity, motion gating, haptics and the GPS budget.
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepository = ServiceLocator.settingsRepository
    private val acquirer = LocationAcquirer(application)

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun setTapSensitivity(value: TapSensitivity) {
        viewModelScope.launch { settingsRepository.setTapSensitivity(value) }
    }

    fun setOnlyWhileMoving(value: Boolean) {
        viewModelScope.launch { settingsRepository.setOnlyWhileMoving(value) }
    }

    fun setHapticFeedback(value: Boolean) {
        viewModelScope.launch { settingsRepository.setHapticFeedback(value) }
    }

    fun setGpsPreWarm(value: Boolean) {
        viewModelScope.launch { settingsRepository.setGpsPreWarm(value) }
    }

    fun setGpsTimeout(seconds: Int) {
        viewModelScope.launch { settingsRepository.setGpsTimeoutSeconds(seconds) }
    }

    fun setCachedPolicy(policy: CachedLocationPolicy) {
        viewModelScope.launch { settingsRepository.setCachedLocationPolicy(policy) }
    }

    fun setMaxAccuracyMeters(value: Float) {
        viewModelScope.launch { settingsRepository.setMaxAccuracyMeters(value) }
    }

    fun hasLocationPermission(): Boolean =
        acquirer.hasPermission()

    fun isGpsReady(): Boolean =
        acquirer.hasPermission() && acquirer.isAnyProviderEnabled()
}
