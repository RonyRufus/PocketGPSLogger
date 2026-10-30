package com.pocketgps.logger.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pocketgps.logger.ServiceLocator
import com.pocketgps.logger.engine.EngineProvider
import com.pocketgps.logger.engine.ListenerUiState
import com.pocketgps.logger.model.ModelManager
import com.pocketgps.logger.settings.AppSettings
import com.pocketgps.logger.settings.CachedLocationPolicy
import com.pocketgps.logger.settings.ConfirmationMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Backs the Settings and Setup screens (settings editing + model management). */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepository = ServiceLocator.settingsRepository
    private val modelManager = ServiceLocator.modelManager

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val engineState: StateFlow<ListenerUiState> = EngineProvider.engine
        .flatMapLatest { engine -> engine?.uiState ?: flowOf(ListenerUiState()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListenerUiState())

    sealed interface ModelOp {
        data object Idle : ModelOp
        data class Working(val message: String, val progress: Float? = null) : ModelOp
        data class Done(val message: String) : ModelOp
        data class Failed(val message: String) : ModelOp
    }

    private val _modelOp = MutableStateFlow<ModelOp>(ModelOp.Idle)
    val modelOp: StateFlow<ModelOp> = _modelOp

    private val _modelStatus = MutableStateFlow<ModelManager.Status>(modelManager.status())
    val modelStatus: StateFlow<ModelManager.Status> = _modelStatus

    private val _storageBytes = MutableStateFlow(modelManager.storageBytes())
    val storageBytes: StateFlow<Long> = _storageBytes

    fun refreshModel() {
        _modelStatus.value = modelManager.status()
        _storageBytes.value = modelManager.storageBytes()
    }

    fun setTriggerPhrase(phrase: String) {
        val phrases = phrase.split('\n', ',', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
        viewModelScope.launch { settingsRepository.setTriggerPhrases(phrases) }
    }

    fun setConfirmationMode(mode: ConfirmationMode) {
        viewModelScope.launch { settingsRepository.setConfirmationMode(mode) }
    }

    fun setGpsTimeout(seconds: Int) {
        viewModelScope.launch { settingsRepository.setGpsTimeoutSeconds(seconds) }
    }

    fun setCachedPolicy(policy: CachedLocationPolicy) {
        viewModelScope.launch { settingsRepository.setCachedLocationPolicy(policy) }
    }

    fun setCachedMaxAge(seconds: Int) {
        viewModelScope.launch { settingsRepository.setCachedMaxAgeSeconds(seconds) }
    }

    fun setMaxAccuracy(meters: Float) {
        viewModelScope.launch { settingsRepository.setMaxAccuracyMeters(meters) }
    }

    fun setCooldown(seconds: Int) {
        viewModelScope.launch { settingsRepository.setCooldownSeconds(seconds) }
    }

    fun setGpsPreWarm(value: Boolean) {
        viewModelScope.launch { settingsRepository.setGpsPreWarm(value) }
    }

    fun setHapticFeedback(value: Boolean) {
        viewModelScope.launch { settingsRepository.setHapticFeedback(value) }
    }

    fun setVoiceNotes(value: Boolean) {
        viewModelScope.launch { settingsRepository.setVoiceNotes(value) }
    }

    fun importModel(uri: Uri) {
        if (EngineProvider.isSessionActive()) {
            _modelOp.value = ModelOp.Failed(STOP_FIRST_MESSAGE)
            return
        }
        viewModelScope.launch {
            _modelOp.value = ModelOp.Working("Extracting model…")
            when (val result = modelManager.importFromZip(uri)) {
                is ModelManager.ImportResult.Success -> {
                    refreshModel()
                    _modelOp.value = ModelOp.Done("Model installed")
                }

                is ModelManager.ImportResult.Failure -> {
                    _modelOp.value = ModelOp.Failed(result.reason)
                }
            }
        }
    }

    fun downloadModel() {
        if (EngineProvider.isSessionActive()) {
            _modelOp.value = ModelOp.Failed(STOP_FIRST_MESSAGE)
            return
        }
        viewModelScope.launch {
            _modelOp.value = ModelOp.Working("Downloading model…", 0f)
            val result = modelManager.downloadAndImport(
                url = ModelManager.LGRAPH_EN_US_URL,
                onProgress = { downloaded, total ->
                    val progress = if (total > 0) downloaded.toFloat() / total else null
                    _modelOp.value = ModelOp.Working("Downloading model…", progress)
                },
            )
            when (result) {
                is ModelManager.ImportResult.Success -> {
                    refreshModel()
                    _modelOp.value = ModelOp.Done("Model installed")
                }

                is ModelManager.ImportResult.Failure -> {
                    _modelOp.value = ModelOp.Failed(result.reason)
                }
            }
        }
    }

    fun deleteModel() {
        if (EngineProvider.isSessionActive()) {
            _modelOp.value = ModelOp.Failed(STOP_FIRST_MESSAGE)
            return
        }
        viewModelScope.launch {
            modelManager.deleteModel()
            refreshModel()
            _modelOp.value = ModelOp.Done("Model deleted")
        }
    }

    fun clearModelOp() {
        _modelOp.value = ModelOp.Idle
    }

    private companion object {
        const val STOP_FIRST_MESSAGE =
            "Stop hands-free listening before changing the speech model"
    }
}
