package com.trafficwatch.app.core.util

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import com.trafficwatch.app.core.domain.model.LocationData
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

private const val LOCATION_UPDATE_INTERVAL_MS = 3_000L
private const val LOCATION_SNAPSHOT_TIMEOUT_MS = 8_000L
const val MAX_ACCEPTABLE_ACCURACY_METERS = 50f

@Singleton
class LocationUtil @Inject constructor(
    private val fusedLocationClient: FusedLocationProviderClient
) {

    /**
     * Returns a single GPS snapshot at the moment of recording start.
     * Times out after [LOCATION_SNAPSHOT_TIMEOUT_MS] ms; returns null if unavailable.
     */
    @SuppressLint("MissingPermission")
    suspend fun getSnapshot(): LocationData? = withTimeoutOrNull(LOCATION_SNAPSHOT_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { location ->
                    continuation.resume(location?.toLocationData())
                }
                .addOnFailureListener {
                    continuation.resume(null)
                }
        }
    }

    /**
     * Continuous location updates as a Flow. Default interval drives the GPS status overlay
     * on CameraScreen; CameraViewModel starts a second, separate subscription at a tighter
     * interval during active recording (see RECORDING_SAMPLE_INTERVAL_MS) - this method
     * itself is stateless per-call, so multiple concurrent subscriptions at different
     * intervals are independent and don't interfere with each other.
     */
    @SuppressLint("MissingPermission")
    fun observeLocation(intervalMs: Long = LOCATION_UPDATE_INTERVAL_MS): Flow<LocationData?> = callbackFlow {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                trySend(result.lastLocation?.toLocationData())
            }
        }

        fusedLocationClient.requestLocationUpdates(request, callback, Looper.getMainLooper())

        awaitClose { fusedLocationClient.removeLocationUpdates(callback) }
    }
}

private fun Location.toLocationData(): LocationData = LocationData(
    latitude = latitude,
    longitude = longitude,
    accuracy = accuracy,
    altitude = altitude,
    bearing = bearing,
    speed = speed,
    capturedAt = System.currentTimeMillis()
)
