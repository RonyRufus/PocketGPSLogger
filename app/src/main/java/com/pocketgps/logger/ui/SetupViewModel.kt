package com.pocketgps.logger.ui

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pocketgps.logger.ServiceLocator
import com.pocketgps.logger.engine.ServiceBus
import com.pocketgps.logger.sensor.TapDetector
import com.pocketgps.logger.sensor.TapSensitivity
import com.pocketgps.logger.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * Backs the setup screen: permissions, battery-optimisation exemption and the **live tap test**
 * that lets the user verify double-tap detection before relying on it in the pocket.
 */
class SetupViewModel(application: Application) : AndroidViewModel(application), SensorEventListener {

    private val settingsRepository = ServiceLocator.settingsRepository
    private val sensorManager =
        application.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val serviceStatus: StateFlow<ServiceBus.Status> = ServiceBus.status

    private var testDetector: TapDetector? = null
    private var testSensitivity = TapSensitivity.NORMAL

    data class TapTestState(
        val running: Boolean = false,
        val spikes: Long = 0,
        val doubleTaps: Long = 0,
        val hasAccelerometer: Boolean = true,
    )

    private val _tapTest = MutableStateFlow(TapTestState())
    val tapTest: StateFlow<TapTestState> = _tapTest

    /** Begin listening with the detector in the UI process. Purely for calibration. */
    fun startTapTest() {
        if (_tapTest.value.running) return
        if (accelerometer == null) {
            _tapTest.value = TapTestState(running = false, hasAccelerometer = false)
            return
        }
        viewModelScope.launch(Dispatchers.Default) {
            val sensitivity = settingsRepository.settings.firstOrNull()?.tapSensitivity
                ?: TapSensitivity.NORMAL
            testSensitivity = sensitivity
            val detector = TapDetector(
                spikeG = TapDetector.spikeFor(sensitivity),
                clock = { android.os.SystemClock.elapsedRealtime() },
            )
            testDetector = detector
            _tapTest.value = TapTestState(running = true, hasAccelerometer = true)
            sensorManager.registerListener(
                this@SetupViewModel,
                accelerometer,
                SensorManager.SENSOR_DELAY_GAME,
            )
        }
    }

    fun stopTapTest() {
        runCatching { sensorManager.unregisterListener(this) }
        testDetector = null
        _tapTest.value = _tapTest.value.copy(running = false)
    }

    fun resetTapTest() {
        testDetector?.reset()
        _tapTest.value = _tapTest.value.copy(spikes = 0, doubleTaps = 0)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val d = testDetector ?: return
        val magnitudeG = sqrt(
            event.values[0] * event.values[0] +
                event.values[1] * event.values[1] +
                event.values[2] * event.values[2],
        ) / 9.80665f
        val timestampMs = android.os.SystemClock.elapsedRealtime() + event.timestamp / 1_000_000L
        d.feed(timestampMs, magnitudeG)
        _tapTest.value = _tapTest.value.copy(
            spikes = d.spikesSeen,
            doubleTaps = d.doubleTaps,
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    fun setTapSensitivity(value: TapSensitivity) {
        viewModelScope.launch { settingsRepository.setTapSensitivity(value) }
    }

    fun hasAccelerometer(): Boolean = accelerometer != null

    fun hasLocationPermission(): Boolean =
        check(android.Manifest.permission.ACCESS_FINE_LOCATION) ||
            check(android.Manifest.permission.ACCESS_COARSE_LOCATION)

    fun hasNotificationPermission(): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
            check(android.Manifest.permission.POST_NOTIFICATIONS)

    /** True when the OEM battery manager is told to leave the foreground service alone. */
    fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getApplication<Application>()
            .getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(getApplication<Application>().packageName)
    }

    private fun check(permission: String): Boolean =
        ContextCompat.checkSelfPermission(getApplication(), permission) ==
            PackageManager.PERMISSION_GRANTED

    override fun onCleared() {
        stopTapTest()
        super.onCleared()
    }
}
