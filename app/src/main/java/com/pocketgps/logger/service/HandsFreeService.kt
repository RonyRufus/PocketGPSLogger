package com.pocketgps.logger.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.pocketgps.logger.R
import com.pocketgps.logger.ServiceLocator
import com.pocketgps.logger.audio.AudioRouteMonitor
import com.pocketgps.logger.audio.ConfirmationPlayer
import com.pocketgps.logger.audio.MicAudioSource
import com.pocketgps.logger.audio.RecognitionPipeline
import com.pocketgps.logger.audio.VoskKeywordSpotter
import com.pocketgps.logger.engine.EngineProvider
import com.pocketgps.logger.engine.ListenerEngine
import com.pocketgps.logger.engine.ListenerState
import com.pocketgps.logger.engine.ListenerUiState
import com.pocketgps.logger.feedback.HapticCue
import com.pocketgps.logger.location.LocationAcquirer
import com.pocketgps.logger.settings.AppSettings
import com.pocketgps.logger.settings.SettingsRepository
import com.pocketgps.logger.ui.main.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Microphone foreground service that hosts the always-on trigger-phrase pipeline.
 *
 * Contract:
 *  - Started only from a visible user interaction (the Start button or the app's notification).
 *  - Declares `foregroundServiceType="microphone"` and holds a bounded PARTIAL_WAKE_LOCK so the
 *    recognizer keeps running with the screen off.
 *  - Keeps AudioRecord + the Vosk recognizer alive for the whole session; never tears them down
 *    between utterances.
 *  - Performs GPS acquisition and Room writes on IO coroutines, never on the audio thread.
 *  - START_NOT_STICKY: the OS may kill the process, and the service is *not* restarted from the
 *    background. The user must start listening again. This is an Android limitation, not an app
 *    choice, and is documented in the README.
 */
class HandsFreeService : android.app.Service() {

    companion object {
        const val CHANNEL_ID = "hands_free_listening"
        const val ACTION_START = "com.pocketgps.logger.action.START"
        const val ACTION_STOP = "com.pocketgps.logger.action.STOP"
        const val ACTION_SAVE_NOW = "com.pocketgps.logger.action.SAVE_NOW"

        private const val NOTIFICATION_ID = 1001
        const val TAG = "HandsFreeService"
        private const val WAKELOCK_TAG = "PocketGPSLogger::Listening"
        private const val WAKELOCK_TIMEOUT_MS = 60L * 60_000L
        private const val WAKELOCK_RENEW_THRESHOLD_MS = 55L * 60_000L

        /**
         * Request the service to start. Must be called while the app is visible.
         *
         * The microphone permission is checked here as well as in the UI: launching a
         * microphone foreground service without it would fail the startForeground contract and
         * crash the process on Android 14.
         */
        fun start(context: Context) {
            val granted = ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
            val intent = Intent(context, HandsFreeService::class.java).setAction(ACTION_START)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        /**
         * Request the service to stop. Safe from the notification action. If the process was
         * killed, there is nothing to stop and [Context.startService] may be rejected from the
         * background; in that case fall back to [Context.stopService].
         */
        fun stop(context: Context) {
            val intent = Intent(context, HandsFreeService::class.java).setAction(ACTION_STOP)
            val started = runCatching { context.startService(intent) }.isSuccess
            if (!started) runCatching { context.stopService(intent) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val audioDispatcher = Dispatchers.IO.limitedParallelism(1)

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var engine: ListenerEngine
    private lateinit var confirmation: ConfirmationPlayer
    private lateinit var locationAcquirer: LocationAcquirer
    private lateinit var routeMonitor: AudioRouteMonitor

    private var pipeline: RecognitionPipeline? = null
    private var mic: MicAudioSource? = null
    private var spotter: VoskKeywordSpotter? = null

    private var audioJob: Job? = null
    private var settingsJob: Job? = null
    private var notificationJob: Job? = null
    private var wakeLockJob: Job? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockHeldSinceMono = 0L
    private var cumulativeWakeLockMs = 0L

    @Volatile private var currentSettings = AppSettings()
    @Volatile private var listeningActive = false

    private val audioManager: AudioManager? by lazy { getSystemService(AudioManager::class.java) }
    private val powerManager: PowerManager by lazy { getSystemService(PowerManager::class.java) }
    private val notificationManager: NotificationManager by lazy {
        getSystemService(NotificationManager::class.java)
    }

    private var headsetReceiverRegistered = false
    private var lastHeadsetConnected: Boolean? = null

    /** Trigger phrase currently compiled into the recognizer grammar. */
    @Volatile private var appliedTriggerPhrases: List<String>? = null
    @Volatile private var previousVoiceNotes: Boolean? = null

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate() {
        super.onCreate()
        try {
            settingsRepository = ServiceLocator.settingsRepository
            confirmation = ConfirmationPlayer(this).also { it.initialize() }
            locationAcquirer = LocationAcquirer(this)
            routeMonitor = AudioRouteMonitor(this)
            engine = ListenerEngine(
                repository = ServiceLocator.locationRepository,
                locationAcquirer = locationAcquirer,
                confirmation = confirmation,
                scope = scope,
                haptics = HapticCue(this),
            )
            engine.startObservingDatabase()
            EngineProvider.set(engine)
            createNotificationChannel()
            registerHeadsetReceiver()
        } catch (t: Throwable) {
            Log.e(TAG, "onCreate failed", t)
            runCatching { if (::confirmation.isInitialized) confirmation.shutdown() }
            runCatching { scope.cancel() }
            throw t
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // A stop request may arrive while listening (already foreground) or after the
            // process was recreated by the system; stopListening() is safe in both cases.
            stopListening()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_SAVE_NOW) {
            // Manual save from the notification. The service is already foreground here;
            // if the engine is not listening yet, saveNow() is a no-op.
            if (::engine.isInitialized) engine.saveNow()
            updateNotification()
            return START_NOT_STICKY
        }

        if (!hasMicrophonePermission()) {
            // Cannot host a microphone FGS without the permission. Report the problem, then
            // stop; the UI drives the permission flow. startForeground is attempted so the
            // startForegroundService contract is not violated if this path is ever reached.
            startForegroundCompat(includeMicrophoneType = false)
            if (::engine.isInitialized) engine.onError("Microphone permission not granted")
            updateNotification()
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat(includeMicrophoneType = true)
        startListening()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        listeningActive = false
        audioJob?.cancel()
        settingsJob?.cancel()
        notificationJob?.cancel()
        wakeLockJob?.cancel()
        releaseAudio()
        releaseWakeLock()
        confirmation.shutdown()
        unregisterHeadsetReceiver()
        if (::engine.isInitialized) {
            EngineProvider.clear(engine)
            engine.shutdown()
        }
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ start / stop

    private fun startListening() {
        if (audioJob?.isActive == true) return
        listeningActive = true

        engine.markInitializing()
        engine.updateReadiness(
            gpsReady = locationAcquirer.hasPermission() && locationAcquirer.isAnyProviderEnabled(),
            microphoneReady = true,
        )
        refreshAudioRoute()

        acquireWakeLock()

        settingsJob?.cancel()
        settingsJob = scope.launch {
            settingsRepository.settings.collect { settings ->
                onSettingsChanged(settings)
            }
        }

        // Keep the fix warm so a voice trigger saves in ~1 s instead of a cold 10–15 s lock.
        if (currentSettings.gpsPreWarm) locationAcquirer.startPreWarm()

        notificationJob?.cancel()
        notificationJob = scope.launch {
            engine.uiState.map { it.state }.distinctUntilChanged().collect {
                updateNotification()
            }
        }

        wakeLockJob?.cancel()
        wakeLockJob = scope.launch {
            while (isActive) {
                delay(30_000)
                renewWakeLockIfNeeded()
                engine.updateReadiness(
                    gpsReady = locationAcquirer.hasPermission() && locationAcquirer.isAnyProviderEnabled(),
                    microphoneReady = true,
                )
            }
        }

        audioJob = scope.launch(audioDispatcher) { runAudioPipeline() }
        updateNotification()
    }

    private fun stopListening() {
        listeningActive = false
        audioJob?.cancel(); audioJob = null
        settingsJob?.cancel(); settingsJob = null
        notificationJob?.cancel(); notificationJob = null
        wakeLockJob?.cancel(); wakeLockJob = null
        locationAcquirer.stopPreWarm()
        releaseAudio()
        releaseWakeLock()
        engine.markDisabled()
        confirmation.shutdown()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ------------------------------------------------------------------ audio pipeline

    private suspend fun runAudioPipeline() {
        var attempt = 0
        while (kotlin.coroutines.coroutineContext.isActive && listeningActive && attempt < 4) {
            attempt++
            try {
                currentSettings = settingsRepository.settings.first()
                confirmation.setMode(currentSettings.confirmationMode)
                engine.updateSettings(currentSettings)

                val model = ServiceLocator.modelManager.loadModel()
                if (model == null) {
                    Log.e(TAG, "Vosk model not loaded; cannot start recognition")
                    failListening("Offline speech model is not installed. Open Setup to install it.")
                    return
                }

                val mic = MicAudioSource().also {
                    it.ensureInitialized() // throws MicException on failure
                    it.start()
                }
                this.mic = mic
                Log.d(TAG, "MicAudioSource initialized and recording started")

                val spotter = VoskKeywordSpotter(
                    model = model,
                    sampleRate = MicAudioSource.SAMPLE_RATE,
                    initialPhrases = currentSettings.triggerPhrases,
                )
                this.spotter = spotter
                Log.d(TAG, "Vosk recognizer created for triggers: ${currentSettings.triggerPhrases}")

                val pl = RecognitionPipeline(
                    audio = mic,
                    spotter = spotter,
                    initialTriggers = currentSettings.triggerPhrases,
                    clock = { SystemClock.elapsedRealtimeNanos() },
                    cooldownSeconds = currentSettings.cooldownSeconds,
                ).also { pipeline ->
                    pipeline.updateDictation(currentSettings.voiceNotes)
                    spotter.setDictation(currentSettings.voiceNotes)
                    pipeline.isSuppressed = { confirmation.isPlaying() }
                    pipeline.onTrigger = { triggerNanos, phrase, _, observation, transcript ->
                        // Convert the monotonic trigger time to wall time, so GPS latency and
                        // dictated-note duration do not shift the saved timestamp.
                        val elapsedMs = ((SystemClock.elapsedRealtimeNanos() - triggerNanos)
                            .coerceAtLeast(0L)) / 1_000_000L
                        val triggerTimestampMs = System.currentTimeMillis() - elapsedMs
                        Log.d(TAG, "Trigger recognized; handing off to engine")
                        engine.onTriggerDetected(
                            triggerTimestampMs, phrase, observation, transcript,
                        )
                        updateNotification()
                    }
                    pipeline.onMuteDetected = { engine.onAudioInterrupted("microphone muted") }
                    pipeline.onMuteCleared = { engine.onAudioRecovered() }
                    pipeline.onSourceError = { msg -> engine.onAudioInterrupted("audio read error: $msg") }
                }
                this.pipeline = pl
                appliedTriggerPhrases = currentSettings.triggerPhrases

                engine.updateReadiness(
                    gpsReady = locationAcquirer.hasPermission() && locationAcquirer.isAnyProviderEnabled(),
                    microphoneReady = true,
                )
                engine.markListening()
                updateNotification()

                var wasInCall = (audioManager?.mode ?: AudioManager.MODE_NORMAL) != AudioManager.MODE_NORMAL
                while (kotlin.coroutines.coroutineContext.isActive && listeningActive) {
                    val inCall = (audioManager?.mode ?: AudioManager.MODE_NORMAL) != AudioManager.MODE_NORMAL
                    if (inCall != wasInCall) {
                        wasInCall = inCall
                        if (inCall) {
                            engine.onAudioInterrupted("phone call / audio focus loss")
                        } else {
                            engine.onAudioRecovered()
                        }
                    }
                    if (inCall) {
                        delay(200)
                        continue
                    }
                    // A false result means AudioRecord itself failed. Restart capture on the
                    // outer retry loop instead of pretending we are still listening.
                    if (!pl.processOneChunk()) break
                }
                if (!listeningActive) return
                // Capture failed (unplugged mic, device lost, privacy toggle). Tear the audio
                // objects down and let the outer loop rebuild them, up to the retry limit.
                engine.onAudioInterrupted("audio input lost — restarting capture")
                releaseAudio()
                delay(500)
            } catch (t: Throwable) {
                if (!kotlin.coroutines.coroutineContext.isActive || !listeningActive) return
                Log.e(TAG, "Audio pipeline crashed", t)
                engine.onAudioInterrupted("audio restart: ${t.message ?: t.javaClass.simpleName}")
                releaseAudio()
                delay(1_000)
            }
        }

        if (listeningActive &&
            engine.uiState.value.state != ListenerState.ERROR &&
            engine.uiState.value.state != ListenerState.DISABLED
        ) {
            failListening("Microphone unavailable — capture could not be restarted")
        }
    }

    /**
     * Fatal listening error: stop audio/wake-lock to avoid draining the battery, but keep the
     * service (and its notification) alive so the user can see what went wrong and tap Stop.
     */
    private fun failListening(message: String) {
        Log.e(TAG, "failListening: $message")
        engine.onError(message)
        releaseAudio()
        releaseWakeLock()
        updateNotification()
    }

    private fun releaseAudio() {
        pipeline = null
        runCatching { spotter?.close() }
        spotter = null
        runCatching { mic?.stop() }
        mic = null
    }

    private fun onSettingsChanged(settings: AppSettings) {
        currentSettings = settings
        engine.updateSettings(settings)
        // Pre-warm follows its toggle live; the subscription is idempotent.
        if (settings.gpsPreWarm && listeningActive) {
            locationAcquirer.startPreWarm()
        } else if (!settings.gpsPreWarm) {
            locationAcquirer.stopPreWarm()
        }
        if (!confirmation.isPlaying()) {
            confirmation.setMode(settings.confirmationMode)
        }
        // Rebuild the grammar only when the phrase actually changed: rebuilding tears down and
        // recreates the native recognizer, which is expensive and loses in-flight audio.
        if (settings.triggerPhrases != appliedTriggerPhrases) {
            appliedTriggerPhrases = settings.triggerPhrases
            pipeline?.updateTriggers(settings.triggerPhrases)
            runCatching { spotter?.updateGrammar(settings.triggerPhrases) }
        }
        if (settings.voiceNotes != previousVoiceNotes) {
            pipeline?.updateDictation(settings.voiceNotes)
            runCatching { spotter?.setDictation(settings.voiceNotes) }
        }
        previousVoiceNotes = settings.voiceNotes
        pipeline?.cooldownSeconds = settings.cooldownSeconds
    }

    // ------------------------------------------------------------------ wake lock

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)
                .apply { setReferenceCounted(false) }
        }
        val wl = wakeLock ?: return
        if (!wl.isHeld) {
            wl.acquire(WAKELOCK_TIMEOUT_MS)
            wakeLockHeldSinceMono = SystemClock.elapsedRealtime()
        }
    }

    private fun renewWakeLockIfNeeded() {
        val wl = wakeLock ?: return
        if (!wl.isHeld) {
            acquireWakeLock()
            return
        }
        if (SystemClock.elapsedRealtime() - wakeLockHeldSinceMono > WAKELOCK_RENEW_THRESHOLD_MS) {
            runCatching { wl.release() }
            wl.acquire(WAKELOCK_TIMEOUT_MS)
            wakeLockHeldSinceMono = SystemClock.elapsedRealtime()
        }
    }

    private fun releaseWakeLock() {
        val wl = wakeLock ?: return
        if (wl.isHeld) {
            cumulativeWakeLockMs += SystemClock.elapsedRealtime() - wakeLockHeldSinceMono
            runCatching { wl.release() }
        }
    }

    // ------------------------------------------------------------------ audio route / headset

    private fun refreshAudioRoute() {
        val route = runCatching { routeMonitor.currentRoute() }.getOrNull() ?: return
        val previous = lastHeadsetConnected
        lastHeadsetConnected = route.headsetOutputConnected
        engine.updateAudioRoute(route.headsetOutputConnected, route.inputSummary)
        // Mute cues only when an earphone that was being used drops mid-session, so we never
        // blare the phone speaker from a pocket. Starting without a headset keeps the user's
        // configured confirmation mode.
        if (listeningActive && previous == true && !route.headsetOutputConnected) {
            confirmation.setSilenced(true)
        } else if (route.headsetOutputConnected) {
            confirmation.setSilenced(false)
        }
    }

    private fun registerHeadsetReceiver() {
        if (headsetReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            addAction(Intent.ACTION_HEADSET_PLUG)
            addAction(android.bluetooth.BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(android.bluetooth.BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
        }
        runCatching {
            ContextCompat.registerReceiver(
                this, headsetReceiver, filter, ContextCompat.RECEIVER_EXPORTED,
            )
            headsetReceiverRegistered = true
        }
    }

    private fun unregisterHeadsetReceiver() {
        if (!headsetReceiverRegistered) return
        runCatching { unregisterReceiver(headsetReceiver) }
        headsetReceiverRegistered = false
    }

    private val headsetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshAudioRoute()
            updateNotification()
        }
    }

    // ------------------------------------------------------------------ notification

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Hands-free listening",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while Pocket GPS Logger is listening for its trigger phrase"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun startForegroundCompat(includeMicrophoneType: Boolean) {
        val type = if (includeMicrophoneType && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(engine.uiState.value), type,
        )
    }

    private fun updateNotification() {
        runCatching {
            notificationManager.notify(NOTIFICATION_ID, buildNotification(engine.uiState.value))
        }
    }

    private fun buildNotification(ui: ListenerUiState): Notification {
        val title: String
        val text: String
        when (ui.state) {
            ListenerState.INITIALIZING -> {
                title = "Starting hands-free mode…"
                text = "Preparing the offline recognizer"
            }

            ListenerState.LISTENING -> {
                title = "Listening for \"${ui.triggerPhrase}\""
                text = ui.lastSaved?.let {
                    "Last saved: ${formatClock(it.triggerTimestamp)}"
                } ?: "Say the phrase to save this spot"
            }

            ListenerState.CAPTURING -> {
                title = "Saving location…"
                text = "Acquiring a GPS fix"
            }

            ListenerState.SAVING -> {
                title = "Saving location…"
                text = "Committing to local storage"
            }

            ListenerState.RECOVERING -> {
                title = "Audio interrupted"
                text = ui.statusMessage ?: "Trying to resume"
            }

            ListenerState.ERROR -> {
                title = "Listening error"
                text = ui.errorMessage?.takeIf { it.isNotBlank() } ?: "Tap to resolve"
            }

            ListenerState.DISABLED -> {
                title = "Listening off"
                text = "Hands-free mode is stopped"
            }
        }

        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val saveIntent = PendingIntent.getService(
            this, 1,
            Intent(this, HandsFreeService::class.java).setAction(ACTION_SAVE_NOW),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this, 2,
            Intent(this, HandsFreeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_location)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .addAction(0, "Save spot now", saveIntent)
            .addAction(0, "Stop listening", stopIntent)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                }
            }
            .build()
    }

    private fun formatClock(timestampMs: Long): String {
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        return fmt.format(java.util.Date(timestampMs))
    }

    // ------------------------------------------------------------------ permissions

    private fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
}
