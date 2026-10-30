package com.pocketgps.logger.ui

import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pocketgps.logger.ServiceLocator
import com.pocketgps.logger.audio.PhraseTestRunner
import com.pocketgps.logger.engine.EngineProvider
import com.pocketgps.logger.engine.ListenerUiState
import com.pocketgps.logger.model.ModelManager
import com.pocketgps.logger.settings.AppSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Backs the guided Setup screen: permissions, model install and phrase test. */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupViewModel(application: Application) : AndroidViewModel(application) {

    private val modelManager = ServiceLocator.modelManager
    private val settingsRepository = ServiceLocator.settingsRepository
    private val testRunner = PhraseTestRunner(modelManager)

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    /** Live listening state, used to disable the phrase test while the service owns the mic. */
    val engineState: StateFlow<ListenerUiState> = EngineProvider.engine
        .flatMapLatest { engine -> engine?.uiState ?: flowOf(ListenerUiState()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListenerUiState())

    sealed interface ModelOp {
        data object Idle : ModelOp
        data class Working(val message: String, val progress: Float? = null) : ModelOp
        data class Done(val message: String) : ModelOp
        data class Failed(val message: String) : ModelOp
    }

    sealed interface TestState {
        data object Idle : TestState
        data object Listening : TestState
        data class Partial(val text: String) : TestState
        data class Finished(val matched: Boolean, val message: String) : TestState
    }

    private val _modelOp = MutableStateFlow<ModelOp>(ModelOp.Idle)
    val modelOp: StateFlow<ModelOp> = _modelOp

    private val _modelStatus = MutableStateFlow<ModelManager.Status>(modelManager.status())
    val modelStatus: StateFlow<ModelManager.Status> = _modelStatus

    private val _storageBytes = MutableStateFlow(modelManager.storageBytes())
    val storageBytes: StateFlow<Long> = _storageBytes

    private val _testState = MutableStateFlow<TestState>(TestState.Idle)
    val testState: StateFlow<TestState> = _testState

    private var testJob: Job? = null

    fun refreshModel() {
        _modelStatus.value = modelManager.status()
        _storageBytes.value = modelManager.storageBytes()
    }

    fun setTriggerPhrase(phrase: String) {
        viewModelScope.launch { settingsRepository.setTriggerPhrase(phrase) }
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

                is ModelManager.ImportResult.Failure -> _modelOp.value = ModelOp.Failed(result.reason)
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

                is ModelManager.ImportResult.Failure -> _modelOp.value = ModelOp.Failed(result.reason)
            }
        }
    }

    fun runPhraseTest(triggerPhrase: String) {
        if (testJob?.isActive == true) return
        if (EngineProvider.isSessionActive()) {
            _testState.value = TestState.Finished(false, "Stop hands-free listening first")
            return
        }
        _testState.value = TestState.Listening
        testJob = viewModelScope.launch {
            val result = testRunner.run(
                triggerPhrase = triggerPhrase,
                durationMs = 8_000L,
                onPartial = { partial ->
                    if (partial.isNotBlank()) _testState.value = TestState.Partial(partial)
                },
            )
            _testState.value = TestState.Finished(result.matched, result.message)
        }
    }

    fun hasMicrophonePermission(): Boolean = check(android.Manifest.permission.RECORD_AUDIO)

    fun hasLocationPermission(): Boolean =
        check(android.Manifest.permission.ACCESS_FINE_LOCATION) ||
            check(android.Manifest.permission.ACCESS_COARSE_LOCATION)

    fun hasNotificationPermission(): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
            check(android.Manifest.permission.POST_NOTIFICATIONS)

    fun isModelReady(): Boolean = modelManager.isReady()

    private fun check(permission: String): Boolean =
        ContextCompat.checkSelfPermission(getApplication<android.app.Application>(), permission) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val STOP_FIRST_MESSAGE =
            "Stop hands-free listening before changing the speech model"
    }
}
