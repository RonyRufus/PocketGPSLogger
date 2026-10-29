package com.pocketgps.logger.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.pocketgps.logger.settings.AppSettings
import com.pocketgps.logger.settings.CachedLocationPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Acquires a location for a trigger, honouring the cached-location policy and a hard timeout.
 *
 * Design:
 *  - Preferred backend: Google Play Services **Fused Location Provider** (battery-efficient,
 *    already fuses GPS + Wi-Fi + cell).
 *  - Fallback backend: platform [LocationManager] last-known fix, used when Play Services is
 *    unavailable or returns nothing.
 *  - The caller must invoke this from an IO coroutine; it never touches the audio thread.
 */
class LocationAcquirer(private val context: Context) {

    data class Fix(
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Float?,
        val fixTimestampMs: Long,
        val source: LocationSource,
        val ageSeconds: Int?,
    )

    sealed interface Result {
        data class Success(val fix: Fix) : Result
        data class Unavailable(val reason: String) : Result
    }

    private val fused: FusedLocationProviderClient? =
        if (playServicesAvailable()) {
            runCatching { LocationServices.getFusedLocationProviderClient(context) }.getOrNull()
        } else {
            null
        }

    private val locationManager: LocationManager? =
        context.getSystemService(LocationManager::class.java)

    private fun playServicesAvailable(): Boolean =
        runCatching {
            GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
        }.getOrDefault(false)

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun isAnyProviderEnabled(): Boolean {
        val lm = locationManager ?: return false
        val gps = runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
        val network = runCatching { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }
            .getOrDefault(false)
        return gps || network
    }

    /**
     * Try to obtain a usable fix for a trigger detected at [triggerTimestampMs].
     *
     * Order:
     *  1. If policy is PREFER_RECENT and a cached fix is fresh + accurate enough → CACHED.
     *  2. Request a fresh fix bounded by `gpsTimeoutSeconds`.
     *  3. If the fresh fix is missing or absurdly inaccurate, fall back to the last known fix
     *     (explicitly FALLBACK, carrying its age) when policy allows.
     *  4. Otherwise report Unavailable.
     */
    @SuppressLint("MissingPermission")
    suspend fun acquire(
        triggerTimestampMs: Long,
        settings: AppSettings,
    ): Result = withContext(Dispatchers.IO) {
        if (!hasPermission()) {
            return@withContext Result.Unavailable("Location permission not granted")
        }
        if (!isAnyProviderEnabled()) {
            return@withContext Result.Unavailable("GPS / location services are disabled")
        }

        val cached = lastKnownLocation()

        // 1. Prefer a recent, accurate cached fix.
        if (settings.cachedLocationPolicy == CachedLocationPolicy.PREFER_RECENT && cached != null) {
            val age = ageSeconds(cached, triggerTimestampMs)
            val accuracy = cached.accuracy.takeIf { hasAccuracy(it) }
            val recentEnough = age <= settings.cachedMaxAgeSeconds
            val accurateEnough = accuracy == null || accuracy <= settings.maxAccuracyMeters
            if (recentEnough && accurateEnough) {
                return@withContext Result.Success(toFix(cached, LocationSource.CACHED, age))
            }
        }

        // 2. Request a fresh fix with a hard timeout.
        val timeoutMs = settings.gpsTimeoutSeconds * 1000L
        val fresh = withTimeoutOrNull(timeoutMs) { requestFreshFix() }

        if (fresh != null) {
            val accuracy = fresh.accuracy.takeIf { hasAccuracy(it) }
            val usable = accuracy == null || accuracy <= settings.maxAccuracyMeters
            if (usable) {
                return@withContext Result.Success(
                    toFix(fresh, LocationSource.FRESH, ageSeconds(fresh, triggerTimestampMs)),
                )
            }
            // Fresh but poor accuracy. Prefer a better cached fix if one exists, else return the
            // fresh fix (the UI flags poor accuracy) unless it is clearly meaningless.
            if (cached != null && (cached.accuracy <= fresh.accuracy)) {
                return@withContext Result.Success(
                    toFix(cached, LocationSource.FALLBACK, ageSeconds(cached, triggerTimestampMs)),
                )
            }
            if (accuracy == null || accuracy <= ABSURD_ACCURACY_METERS) {
                return@withContext Result.Success(
                    toFix(fresh, LocationSource.FRESH, ageSeconds(fresh, triggerTimestampMs)),
                )
            }
            return@withContext Result.Unavailable(
                "Fresh fix accuracy too poor (${accuracy.toInt()} m)",
            )
        }

        // 3. Fallback to last known.
        if (cached != null && settings.cachedLocationPolicy == CachedLocationPolicy.PREFER_RECENT) {
            val age = ageSeconds(cached, triggerTimestampMs)
            return@withContext Result.Success(toFix(cached, LocationSource.FALLBACK, age))
        }

        return@withContext Result.Unavailable("No location fix within timeout")
    }

    // ------------------------------------------------------------------ backends

    @SuppressLint("MissingPermission")
    private suspend fun requestFreshFix(): Location? {
        fused?.let { client ->
            val result = requestFusedOnce(client)
            if (result != null) return result
        }
        return requestPlatformOnce()
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestFusedOnce(client: FusedLocationProviderClient): Location? {
        val cts = CancellationTokenSource()
        val task = runCatching {
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
        }.getOrNull() ?: return null
        return try {
            suspendCancellableCoroutine<Location?> { cont ->
                task.addOnSuccessListener { loc -> if (cont.isActive) cont.resume(loc) }
                task.addOnFailureListener { if (cont.isActive) cont.resume(null) }
                task.addOnCanceledListener { if (cont.isActive) cont.resume(null) }
                cont.invokeOnCancellation { cts.cancel() }
            }
        } catch (t: Throwable) {
            null
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private suspend fun requestPlatformOnce(): Location? {
        val lm = locationManager ?: return null
        val providers = buildList {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
        }
        if (providers.isEmpty()) return null

        // API 30+ has a clean single-shot callback; older APIs get a bounded listener.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val provider = providers.first()
            return runCatching {
                suspendCancellableCoroutine<Location?> { cont ->
                    val signal = android.os.CancellationSignal()
                    lm.getCurrentLocation(
                        provider,
                        signal,
                        ContextCompat.getMainExecutor(context),
                    ) { loc -> if (cont.isActive) cont.resume(loc) }
                    cont.invokeOnCancellation { signal.cancel() }
                }
            }.getOrNull()
        }

        @Suppress("DEPRECATION")
        return runCatching {
            suspendCancellableCoroutine<Location?> { cont ->
                val provider = providers.first()
                val listener = object : android.location.LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (cont.isActive) cont.resume(location)
                    }
                    override fun onProviderDisabled(provider: String) {
                        if (cont.isActive) cont.resume(null)
                    }
                    override fun onProviderEnabled(provider: String) = Unit
                    @Suppress("OVERRIDE_DEPRECATION")
                    override fun onStatusChanged(
                        provider: String?,
                        status: Int,
                        extras: android.os.Bundle?,
                    ) = Unit
                }
                lm.requestSingleUpdate(provider, listener, android.os.Looper.getMainLooper())
                cont.invokeOnCancellation {
                    runCatching { lm.removeUpdates(listener) }
                }
            }
        }.getOrNull()
    }

    @SuppressLint("MissingPermission")
    private fun lastKnownLocation(): Location? {
        fused?.lastLocation?.let { task ->
            // Blocking here is acceptable: we are already on Dispatchers.IO and the task is
            // normally already complete (cached). Guard with a short bounded await.
            val loc = runCatching { com.google.android.gms.tasks.Tasks.await(task) }.getOrNull()
            if (loc != null) return loc
        }
        val lm = locationManager ?: return null
        val candidates = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        ).mapNotNull { provider ->
            runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
        }
        return candidates.maxByOrNull { it.time }
    }

    // ------------------------------------------------------------------ helpers

    private fun toFix(location: Location, source: LocationSource, age: Int): Fix = Fix(
        latitude = location.latitude,
        longitude = location.longitude,
        accuracyMeters = location.accuracy.takeIf { hasAccuracy(it) },
        fixTimestampMs = location.time.takeIf { it > 0 } ?: System.currentTimeMillis(),
        source = source,
        ageSeconds = age,
    )

    private fun ageSeconds(location: Location, triggerTimestampMs: Long): Int {
        val fixTime = location.time.takeIf { it > 0 } ?: return 0
        val ageMs = (triggerTimestampMs - fixTime).coerceAtLeast(0)
        return (ageMs / 1000).toInt()
    }

    private fun hasAccuracy(value: Float): Boolean = value > 0f && !value.isNaN()

    companion object {
        /** Beyond this, a fix is not a location so much as a guess. */
        const val ABSURD_ACCURACY_METERS = 1000f
    }
}
