package com.pocketgps.logger.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.pocketgps.logger.ServiceLocator
import com.pocketgps.logger.engine.SavePipeline
import com.pocketgps.logger.engine.ServiceBus
import com.pocketgps.logger.sensor.TapDetector
import com.pocketgps.logger.sensor.TapSensitivity
import com.pocketgps.logger.settings.AppSettings
import com.pocketgps.logger.settings.SettingsRepository
import com.pocketgps.logger.ui.main.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * The v2 core: a foreground service that turns **double-taps on the pocketed, locked phone**
 * into saved GPS spots.
 *
 * Efficiency law, implemented in three tiers:
 *
 *  1. **Parked (stationary)** — when the phone has not moved for [PARK_AFTER_MS] (and
 *     "listen only while moving" is on), the accelerometer listener, the GPS pre-warm and the
 *     wake lock are all released. A hardware *significant-motion* sensor — which costs almost
 *     nothing — stays armed and wakes everything the moment the user starts moving.
 *  2. **Moving** — accelerometer at ~50 Hz + partial wake lock + low-power GPS pre-warm. The
 *     tap detector runs per sample with zero allocations.
 *  3. **Saving** — a single high-accuracy fix request bounded by the GPS timeout, one Room
 *     insert, one haptic pulse. Never continuous GPS.
 *
 * Feedback contract: one haptic pulse = saved, double pulse = failed (see [HapticCue]). The
 * notification carries Save now / Undo / Stop so the phone never needs to be unlocked.
 */
class TapLogService : Service(), SensorEventListener {

    /**
     * Significant-motion trigger listener. Separate from [SensorEventListener] because the
     * hardware trigger API requires a [TriggerEventListener]. Fires once, auto-cancels, and is
     * re-armed in [reevaluateSensors].
     */
    private val motionListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            sgmRegistered = false
            lastMotionElapsedMs = SystemClock.elapsedRealtime()
            if (ServiceBus.status.value.isRunning) {
                parked = false
                reevaluateSensors()
                if (currentSettings.gpsPreWarm) pipeline.acquirer.startPreWarm()
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var pipeline: SavePipeline
    private lateinit var sensorManager: SensorManager
    private var detector: TapDetector? = null
    private var accelerometer: Sensor? = null
    private var significantMotion: Sensor? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile private var accelRegistered = false
    @Volatile private var sgmRegistered = false
    @Volatile private var parked = false
    @Volatile private var currentSettings = AppSettings()

    /** Boots → event-time mapping so sensor timestamps land in the wall-clock domain. */
    private var wallAtBootMs = 0L

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate() {
        super.onCreate()
        settingsRepository = ServiceLocator.settingsRepository
        pipeline = ServiceLocator.savePipeline
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        significantMotion = sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
        wallAtBootMs = System.currentTimeMillis() - SystemClock.elapsedRealtime()

        scope.launch {
            settingsRepository.settings.collectLatest { settings ->
                val wasSensitivity = detector?.spikeG
                currentSettings = settings
                if (wasSensitivity != TapDetector.spikeFor(settings.tapSensitivity)) {
                    detector?.reset()
                }
                if (ServiceBus.status.value.isRunning) {
                    if (settings.gpsPreWarm && !parked) pipeline.acquirer.startPreWarm()
                    reevaluateSensors()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopServiceInternally("Stopped")
                return START_NOT_STICKY
            }

            ACTION_SAVE_NOW -> {
                saveNow("notification")
                return START_STICKY
            }

            ACTION_UNDO -> {
                undoLastSave()
                return START_STICKY
            }

            else -> startSession()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseEverything()
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ session

    private fun startSession() {
        if (!hasLocationPermission()) {
            ServiceBus.update {
                it.copy(
                    state = ServiceBus.State.ERROR,
                    errorMessage = "Location permission is missing — open the app to grant it.",
                )
            }
            stopSelf()
            return
        }
        if (accelerometer == null) {
            ServiceBus.update {
                it.copy(
                    state = ServiceBus.State.ERROR,
                    errorMessage = "This device has no accelerometer — tap detection is unavailable.",
                )
            }
            stopSelf()
            return
        }

        startForegroundWithNotification()

        ServiceBus.update {
            ServiceBus.Status(
                state = ServiceBus.State.ARMED,
                message = "Double-tap your pocket to save a spot.",
            )
        }

        if (currentSettings.gpsPreWarm) pipeline.acquirer.startPreWarm()
        reevaluateSensors()
        scheduleParkCheck()
    }

    private fun stopServiceInternally(message: String) {
        releaseEverything()
        ServiceBus.update {
            it.copy(state = ServiceBus.State.OFF, message = message, motionGated = false)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Release every resource the session holds; safe to call repeatedly. */
    private fun releaseEverything() {
        if (accelRegistered) {
            runCatching { sensorManager.unregisterListener(this) }
            accelRegistered = false
        }
        if (sgmRegistered) {
            runCatching { sensorManager.cancelTriggerSensor(motionListener, significantMotion) }
            sgmRegistered = false
        }
        pipeline.acquirer.stopPreWarm()
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = null
        parked = false
    }

    // ------------------------------------------------------------------ efficiency tiers

    /**
     * Decide what should be listening right now:
     *  - always: the significant-motion trigger (hardware, ~zero cost);
     *  - when moving, or "only while moving" is off: accelerometer + wake lock (+ pre-warm);
     *  - when parked: none of the above.
     */
    private fun reevaluateSensors() {
        val listenNow = !parked
        if (listenNow && !accelRegistered) {
            accelerometer?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
                accelRegistered = true
                acquireWakeLock()
            }
        } else if (!listenNow && accelRegistered) {
            runCatching { sensorManager.unregisterListener(this, accelerometer) }
            accelRegistered = false
            wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
            wakeLock = null
            pipeline.acquirer.stopPreWarm()
        }

        if (!sgmRegistered) {
            significantMotion?.let {
                // Trigger sensors auto-cancel after firing; motionListener re-arms.
                sensorManager.requestTriggerSensor(motionListener, it)
                sgmRegistered = true
            }
        }

        ServiceBus.update { it.copy(motionGated = parked) }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(WAKELOCK_TIMEOUT_MS)
        }
    }

    /**
     * Park the sensor loop after [PARK_AFTER_MS] without movement. Runs on the service scope;
     * reschedules itself; exits when the service stops.
     */
    private fun scheduleParkCheck() {
        scope.launch {
            while (ServiceBus.status.value.isRunning) {
                kotlinx.coroutines.delay(PARK_CHECK_INTERVAL_MS)
                if (!currentSettings.onlyWhileMoving) continue
                if (SystemClock.elapsedRealtime() - lastMotionElapsedMs > PARK_AFTER_MS && !parked) {
                    parked = true
                    reevaluateSensors()
                    ServiceBus.update {
                        it.copy(
                            motionGated = true,
                            message = "Parked — battery saver on. Move to re-arm tap detection.",
                        )
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ sensor callbacks

    @Volatile private var lastMotionElapsedMs = SystemClock.elapsedRealtime()

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val magnitudeG = sqrt(x * x + y * y + z * z) / 9.80665f
                val timestampMs = wallAtBootMs + event.timestamp / 1_000_000L
                val d = detector ?: return
                val confirmed = d.feed(timestampMs, magnitudeG)
                if (confirmed > 0) onDoubleTap()
                if (d.spikesSeen != lastReportedSpikes) {
                    lastReportedSpikes = d.spikesSeen
                    ServiceBus.update { it.copy(tapsDetected = d.spikesSeen) }
                }
            }
        }
    }

    @Volatile private var lastReportedSpikes = 0L

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // ------------------------------------------------------------------ saving

    private fun onDoubleTap() {
        ServiceBus.update { it.copy(state = ServiceBus.State.CAPTURING) }
        saveNow("double tap")
    }

    private fun saveNow(triggerLabel: String) {
        scope.launch {
            ServiceBus.update { it.copy(state = ServiceBus.State.SAVING) }
            val settings = currentSettings
            when (val result = pipeline.save(triggerLabel, settings)) {
                is SavePipeline.Result.Saved -> {
                    val note = result.warning?.let { " (saved, but: $it)" }
                        ?: "Saved ${FormattersClock.time(result.record.triggerTimestamp)}"
                    ServiceBus.update {
                        it.copy(
                            state = ServiceBus.State.ARMED,
                            savesThisSession = it.savesThisSession + 1,
                            lastSavedAtMs = pipeline.lastSavedAtMs,
                            message = note,
                        )
                    }
                }

                is SavePipeline.Result.Unavailable -> ServiceBus.update {
                    it.copy(
                        state = ServiceBus.State.ARMED,
                        message = "Could not save: ${result.reason}",
                    )
                }

                is SavePipeline.Result.WriteFailed -> ServiceBus.update {
                    it.copy(state = ServiceBus.State.ARMED, message = "Save failed: ${result.reason}")
                }
            }
            refreshNotification()
        }
    }

    private fun undoLastSave() {
        scope.launch {
            val undone = runCatching { pipeline.undoLast() }.getOrDefault(false)
            ServiceBus.update {
                it.copy(
                    state = ServiceBus.State.ARMED,
                    message = if (undone) "Last save undone" else "Nothing to undo",
                    savesThisSession = if (undone) (it.savesThisSession - 1).coerceAtLeast(0) else it.savesThisSession,
                )
            }
            refreshNotification()
        }
    }

    // ------------------------------------------------------------------ notification

    private fun startForegroundWithNotification() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Tap logging",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { setShowBadge(false) },
            )
        }
        val notification = buildNotification(null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun refreshNotification() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(
            NOTIFICATION_ID,
            buildNotification(ServiceBus.status.value.message),
        )
    }

    @SuppressLint("MissingPermission")
    private fun buildNotification(message: String?): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        fun action(action: String, title: String, requestCode: Int): Notification.Action {
            val pi = PendingIntent.getService(
                this,
                requestCode,
                Intent(this, TapLogService::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return Notification.Action.Builder(null, title, pi).build()
        }

        val status = ServiceBus.status.value
        val text = when {
            status.motionGated -> "Parked — move to re-arm"
            else -> "Double-tap through your pocket to save"
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setContentTitle("Tap logging active")
            .setContentText(if (message != null) message else text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .addAction(action(ACTION_SAVE_NOW, "Save now", 1))
            .addAction(action(ACTION_UNDO, "Undo last", 2))
            .addAction(action(ACTION_STOP, "Stop", 3))
            .build()
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------------ static controls

    companion object {
        const val CHANNEL_ID = "tap_logging"
        const val ACTION_START = "com.pocketgps.logger.action.START"
        const val ACTION_STOP = "com.pocketgps.logger.action.STOP"
        const val ACTION_SAVE_NOW = "com.pocketgps.logger.action.SAVE_NOW"
        const val ACTION_UNDO = "com.pocketgps.logger.action.UNDO"

        private const val NOTIFICATION_ID = 2001
        private const val WAKELOCK_TAG = "PocketGPSLogger::TapListen"
        private const val WAKELOCK_TIMEOUT_MS = 12 * 60 * 60 * 1000L

        /** Park the sensor loop after this long without significant motion. */
        private const val PARK_AFTER_MS = 10 * 60 * 1000L
        private const val PARK_CHECK_INTERVAL_MS = 60 * 1000L

        fun start(context: Context) {
            val intent = Intent(context, TapLogService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, TapLogService::class.java).setAction(ACTION_STOP),
            )
        }

        fun saveNow(context: Context) {
            context.startService(
                Intent(context, TapLogService::class.java).setAction(ACTION_SAVE_NOW),
            )
        }

        fun undoLast(context: Context) {
            context.startService(
                Intent(context, TapLogService::class.java).setAction(ACTION_UNDO),
            )
        }
    }
}

/** Tiny formatting helper kept separate so the service has no Compose/UI imports. */
private object FormattersClock {
    fun time(ms: Long): String =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ms))
}
