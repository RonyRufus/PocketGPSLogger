package com.pocketgps.logger.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.sqrt

/**
 * Detects a deliberate double physical tap while the foreground capture service is active.
 *
 * Uses a hardware linear-acceleration sensor when available, otherwise subtracts a low-pass
 * gravity estimate from the accelerometer. It allocates no objects in the sensor hot path and
 * never starts a service itself. The owning foreground service controls its lifetime.
 */
internal class PocketTapDetector(
    context: Context,
    private val onDoubleTap: () -> Unit,
) : SensorEventListener {

    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    // Prefer wake-up sensors: ordinary accelerometers are often suspended when the screen turns off.
    // A non-wakeup fallback can work on some devices, but cannot guarantee screen-off delivery.
    private val sensor: Sensor? =
        manager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION, true)
            ?: manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true)
            ?: manager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            ?: manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    val hasWakeUpSensor: Boolean get() = sensor?.isWakeUpSensor == true
    private val classifier = DoubleTapClassifier()

    private var registered = false
    private var gx = 0f
    private var gy = 0f
    private var gz = 0f
    private var gravityInitialized = false
    private var lastImpactAt = Long.MIN_VALUE

    val isSupported: Boolean get() = sensor != null

    fun start(): Boolean {
        if (registered) return true
        val selected = sensor ?: return false
        classifier.reset()
        gravityInitialized = false
        registered = manager.registerListener(this, selected, SensorManager.SENSOR_DELAY_UI)
        return registered
    }

    fun stop() {
        if (registered) manager.unregisterListener(this)
        registered = false
        classifier.reset()
        lastImpactAt = Long.MIN_VALUE
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!registered || event.sensor.type != sensor?.type) return
        val values = event.values
        if (values.size < 3) return

        val ax: Float
        val ay: Float
        val az: Float
        if (event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) {
            ax = values[0]
            ay = values[1]
            az = values[2]
        } else {
            // Low-pass gravity estimate; high-pass remainder approximates linear acceleration.
            val alpha = 0.82f
            if (!gravityInitialized) {
                gx = values[0]
                gy = values[1]
                gz = values[2]
                gravityInitialized = true
                return
            }
            gx = alpha * gx + (1f - alpha) * values[0]
            gy = alpha * gy + (1f - alpha) * values[1]
            gz = alpha * gz + (1f - alpha) * values[2]
            ax = values[0] - gx
            ay = values[1] - gy
            az = values[2] - gz
        }

        val magnitude = sqrt(ax * ax + ay * ay + az * az)
        val now = SystemClock.elapsedRealtime()
        // One impact often produces several adjacent high samples; collapse them into one event.
        if (magnitude >= IMPACT_THRESHOLD && now - lastImpactAt >= IMPACT_DEBOUNCE_MS) {
            lastImpactAt = now
            if (classifier.onImpact(magnitude, now)) onDoubleTap()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        private const val IMPACT_THRESHOLD = 8.5f
        private const val IMPACT_DEBOUNCE_MS = 90L
    }
}
