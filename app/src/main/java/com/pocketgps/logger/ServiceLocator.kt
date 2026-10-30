package com.pocketgps.logger

import android.content.Context
import com.pocketgps.logger.data.LocationDatabase
import com.pocketgps.logger.engine.SavePipeline
import com.pocketgps.logger.feedback.HapticCue
import com.pocketgps.logger.location.LocationAcquirer
import com.pocketgps.logger.location.LocationRepository
import com.pocketgps.logger.settings.SettingsRepository

/**
 * Process-wide singletons. Initialised once from [App]; touched from the service, view models
 * and Compose UI (all in the same process).
 */
object ServiceLocator {

    @Volatile
    private var initialised = false

    lateinit var locationRepository: LocationRepository
        private set

    lateinit var settingsRepository: SettingsRepository
        private set

    /**
     * One save pipeline shared by the foreground service (taps, notification buttons) and the
     * UI (save button, undo). Sharing it means "undo last" works no matter which path saved.
     */
    lateinit var savePipeline: SavePipeline
        private set

    fun init(context: Context) {
        if (initialised) return
        synchronized(this) {
            if (initialised) return
            val app = context.applicationContext
            locationRepository = LocationRepository(LocationDatabase.get(app).locationDao())
            settingsRepository = SettingsRepository(app)
            savePipeline = SavePipeline(
                repository = locationRepository,
                acquirer = LocationAcquirer(app),
                haptics = HapticCue(app),
            )
            initialised = true
        }
    }
}
