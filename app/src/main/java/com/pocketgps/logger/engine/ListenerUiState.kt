package com.pocketgps.logger.engine

import com.pocketgps.logger.data.LocationRecord

/**
 * Everything the UI needs to render the listening screen, published as a single StateFlow.
 */
data class ListenerUiState(
    val state: ListenerState = ListenerState.DISABLED,
    val triggerPhrase: String = "save spot",
    val lastSaved: LocationRecord? = null,
    val savedCount: Int = 0,
    /** Transient status line, e.g. "Location saved" or "Location unavailable". */
    val statusMessage: String? = null,
    /** Set only when listening must stop until the user resolves an issue. */
    val errorMessage: String? = null,
    /** True when GPS permission is granted and a provider is enabled. */
    val gpsReady: Boolean = false,
    /** True when microphone permission is granted. */
    val microphoneReady: Boolean = false,
    /** Whether a wired or Bluetooth headset output is currently connected. */
    val headsetConnected: Boolean = false,
    /** Human-readable summary of the actual microphone input route, when known. */
    val inputRoute: String? = null,
    /** Wall-clock time of the most recent state change, for the UI's relative timestamp. */
    val lastEventAtMs: Long = 0L,
) {
    val isListening: Boolean
        get() = state == ListenerState.LISTENING ||
            state == ListenerState.CAPTURING ||
            state == ListenerState.SAVING
}
