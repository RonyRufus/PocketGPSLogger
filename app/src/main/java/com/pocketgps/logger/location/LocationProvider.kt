package com.pocketgps.logger.location

import com.pocketgps.logger.settings.AppSettings

/**
 * Indirection over [LocationAcquirer] so the engine's capture flow (GPS success/failure →
 * record semantics) can be unit-tested with a fake provider on the JVM.
 */
fun interface LocationProvider {
    suspend fun acquire(settings: AppSettings): LocationAcquirer.Result
}
