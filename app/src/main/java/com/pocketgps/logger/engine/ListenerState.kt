package com.pocketgps.logger.engine

/**
 * High-level states of the hands-free listening lifecycle.
 */
enum class ListenerState {
    /** Listening is off; microphone and wake lock are fully released. */
    DISABLED,

    /** Loading the offline model and initialising AudioRecord. */
    INITIALIZING,

    /** Listening for the trigger phrase. */
    LISTENING,

    /** Trigger accepted; a GPS fix is being acquired. Audio capture continues independently. */
    CAPTURING,

    /** Fix acquired; committing the record to Room. */
    SAVING,

    /** Temporary audio interruption (phone call, muted mic, read error). */
    RECOVERING,

    /** Listening cannot continue until the user resolves the problem. */
    ERROR,
}
