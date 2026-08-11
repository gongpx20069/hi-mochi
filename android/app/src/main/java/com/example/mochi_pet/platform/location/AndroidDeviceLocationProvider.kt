package com.example.mochi_pet.platform.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import com.example.mochi_pet.core.location.DeviceLocation
import com.example.mochi_pet.core.location.DeviceLocationProvider
import com.example.mochi_pet.core.location.LocationPermissionDeniedException
import com.example.mochi_pet.core.location.LocationRequestTimeoutException
import com.example.mochi_pet.core.location.LocationUnavailableException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

class LocationPermissionGate(
    private val hasPermission: () -> Boolean,
) {
    private val mutableRequest = MutableStateFlow(false)
    private var pending: CompletableDeferred<Boolean>? = null

    val request: StateFlow<Boolean> = mutableRequest.asStateFlow()

    suspend fun awaitPermission(): Boolean {
        if (hasPermission()) {
            return true
        }
        val deferred = synchronized(this) {
            pending ?: CompletableDeferred<Boolean>().also {
                pending = it
                mutableRequest.value = true
            }
        }
        return deferred.await()
    }

    fun resolve(granted: Boolean) {
        val deferred = synchronized(this) {
            mutableRequest.value = false
            pending.also { pending = null }
        }
        deferred?.complete(granted)
    }
}

class AndroidDeviceLocationProvider(
    context: Context,
    private val permissionGate: LocationPermissionGate,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : DeviceLocationProvider {
    private val appContext = context.applicationContext
    private val locationManager =
        appContext.getSystemService(LocationManager::class.java)

    @SuppressLint("MissingPermission")
    override suspend fun currentLocation(): DeviceLocation {
        if (!permissionGate.awaitPermission()) {
            throw LocationPermissionDeniedException()
        }
        val provider = when {
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ->
                LocationManager.NETWORK_PROVIDER
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ->
                LocationManager.GPS_PROVIDER
            else -> throw LocationUnavailableException(
                "Enable device location to determine the current position",
            )
        }
        val now = nowMillis()
        val lastKnown = listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
        ).mapNotNull { candidate ->
            runCatching {
                locationManager.getLastKnownLocation(candidate)
            }.getOrNull()
        }.filter { location ->
            location.time > 0 &&
                now - location.time in 0..MAX_LAST_KNOWN_AGE_MILLIS
        }.maxByOrNull(Location::getTime)
        val location = lastKnown ?: try {
            withTimeout(LOCATION_TIMEOUT_MILLIS) {
                requestLocation(provider)
            }
        } catch (error: TimeoutCancellationException) {
            throw LocationRequestTimeoutException()
        }
        return DeviceLocation(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = location.accuracy
                .takeIf { location.hasAccuracy() }
                ?.toDouble(),
            capturedAtEpochMillis = location.time.takeIf { it > 0 },
            provider = location.provider,
        )
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    private suspend fun requestLocation(provider: String): Location =
        suspendCancellableCoroutine { continuation ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val cancellationSignal = CancellationSignal()
                continuation.invokeOnCancellation {
                    cancellationSignal.cancel()
                }
                locationManager.getCurrentLocation(
                    provider,
                    cancellationSignal,
                    ContextCompat.getMainExecutor(appContext),
                ) { location ->
                    if (location == null) {
                        continuation.resumeWithException(
                            LocationUnavailableException(
                                "Could not determine the current location",
                            ),
                        )
                    } else {
                        continuation.resume(location)
                    }
                }
                return@suspendCancellableCoroutine
            }

            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    locationManager.removeUpdates(this)
                    if (continuation.isActive) {
                        continuation.resume(location)
                    }
                }

                override fun onProviderDisabled(provider: String) = Unit

                override fun onProviderEnabled(provider: String) = Unit

                @Deprecated("Deprecated in Android")
                override fun onStatusChanged(
                    provider: String?,
                    status: Int,
                    extras: Bundle?,
                ) = Unit
            }
            continuation.invokeOnCancellation {
                locationManager.removeUpdates(listener)
            }
            locationManager.requestSingleUpdate(
                provider,
                listener,
                Looper.getMainLooper(),
            )
        }

    private companion object {
        const val LOCATION_TIMEOUT_MILLIS = 12_000L
        const val MAX_LAST_KNOWN_AGE_MILLIS = 5 * 60 * 1_000L
    }
}
