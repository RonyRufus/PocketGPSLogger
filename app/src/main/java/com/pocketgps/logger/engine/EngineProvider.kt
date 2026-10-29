package com.pocketgps.logger.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds the engine instance created by the foreground service so the Compose UI (same process)
 * can observe the single source of truth without binding IPC.
 */
object EngineProvider {
    private val _engine = MutableStateFlow<ListenerEngine?>(null)
    val engine: StateFlow<ListenerEngine?> = _engine.asStateFlow()

    /** The current engine, if a listening session is alive. */
    fun current(): ListenerEngine? = _engine.value

    /**
     * True while a listening session owns the recognizer or microphone (starting, listening,
     * capturing, saving or recovering). Used to block actions that would tear down the native
     * recognizer or contend for the microphone.
     */
    fun isSessionActive(): Boolean {
        val state = _engine.value?.uiState?.value?.state ?: return false
        return state != ListenerState.DISABLED && state != ListenerState.ERROR
    }

    fun set(engine: ListenerEngine) {
        _engine.value = engine
    }

    fun clear(engine: ListenerEngine) {
        if (_engine.value === engine) _engine.value = null
    }
}
