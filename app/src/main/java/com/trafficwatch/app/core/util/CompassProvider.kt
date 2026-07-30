package com.trafficwatch.app.core.util

import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

private const val DEFAULT_TIMEOUT_MS = 2_000L
private const val SAMPLE_COUNT = 3

/**
 * Returns a single true-north compass heading snapshot at the moment of recording start -
 * the primary signal for translating in-frame vehicle movement (from the video-analysis
 * service) into a real-world compass bearing, mirroring [LocationUtil]'s single-snapshot
 * shape.
 */
@Singleton
class CompassProvider @Inject constructor(
    private val sensorManager: SensorManager,
) {

    /**
     * Reads [Sensor.TYPE_ROTATION_VECTOR] (fused/filtered, simpler and more accurate than
     * raw magnetometer+accelerometer) for a short window, averages a few samples for
     * stability, then corrects magnetic north to true north using [latitude]/[longitude]/
     * [altitude] (from the location snapshot captured at the same instant). Returns null if
     * no rotation-vector sensor exists or the read times out within [timeoutMs].
     */
    suspend fun getSnapshot(
        latitude: Double,
        longitude: Double,
        altitude: Double,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Float? = withTimeoutOrNull(timeoutMs) {
        val magneticAzimuthDegrees = readAveragedAzimuthDegrees() ?: return@withTimeoutOrNull null
        applyDeclination(magneticAzimuthDegrees, latitude, longitude, altitude)
    }

    private suspend fun readAveragedAzimuthDegrees(): Float? = suspendCancellableCoroutine { continuation ->
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sensor == null) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }

        val samples = mutableListOf<Float>()
        val rotationMatrix = FloatArray(9)
        val orientation = FloatArray(3)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientation)
                val azimuthDegrees = (Math.toDegrees(orientation[0].toDouble()).toFloat() + 360f) % 360f
                samples.add(azimuthDegrees)

                if (samples.size >= SAMPLE_COUNT) {
                    sensorManager.unregisterListener(this)
                    if (continuation.isActive) {
                        continuation.resume(circularMeanDegrees(samples))
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        continuation.invokeOnCancellation { sensorManager.unregisterListener(listener) }
    }

    private fun applyDeclination(
        magneticAzimuthDegrees: Float,
        latitude: Double,
        longitude: Double,
        altitude: Double,
    ): Float {
        val declination = GeomagneticField(
            latitude.toFloat(),
            longitude.toFloat(),
            altitude.toFloat(),
            System.currentTimeMillis(),
        ).declination
        return (magneticAzimuthDegrees + declination + 360f) % 360f
    }

    /** Arithmetic mean breaks near the 0/360 wrap-around, so this averages on the unit circle. */
    private fun circularMeanDegrees(samplesDegrees: List<Float>): Float {
        val sinSum = samplesDegrees.sumOf { sin(Math.toRadians(it.toDouble())) }
        val cosSum = samplesDegrees.sumOf { cos(Math.toRadians(it.toDouble())) }
        val meanRadians = atan2(sinSum, cosSum)
        return (Math.toDegrees(meanRadians).toFloat() + 360f) % 360f
    }
}
