package com.pocketgps.logger.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide publish/subscribe between [com.pocketgps.logger.service.TapLogService] and the
 * Compose UI (same process, no binding/IPC). The service writes; screens observe.
 */
object ServiceBus {

    /** Lifecycle of the tap-logging foreground service. */
    enum class State { OFF, STARTING, ARMED, CAPTURING, SAVING, ERROR }

    data class Status(
        val state: State = State.OFF,
        /** True when the sensor loop is parked because the phone has not moved for a while. */
        val motionGated: Boolean = false,
        /** Raw spikes seen since the service started (calibration visibility). */
        val tapsDetected: Long = 0,
        /** Spots saved during this service session. */
        val savesThisSession: Int = 0,
        /** Wall-clock ms of the last save in this session, 0 if none. */
        val lastSavedAtMs: Long = 0L,
        /** Short human-readable status line for the UI. */
        val message: String? = null,
        /** Set when the service cannot continue (missing permission, sensors absent…). */
        val errorMessage: String? = null,
    ) {
        val isRunning: Boolean
            get() = state != State.OFF
    }

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    fun update(transform: (Status) -> Status) {
        _status.value = transform(_status.value)
    }

    fun reset() {
        _status.value = Status()
    }
}
