package com.trafficwatch.app.core.util

import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
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
 * shape. [observeHeadings] is the continuous counterpart, used during active recording.
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
        val declination = declinationDegrees(latitude, longitude, altitude)
        applyDeclination(magneticAzimuthDegrees, declination)
    }

    /**
     * Continuous version of [getSnapshot] - emits a declination-corrected heading on every
     * sensor update, requested at approximately [intervalMs] apart (best-effort; the OS
     * doesn't guarantee exact timing, same as [LocationUtil.observeLocation]'s interval
     * request). Declination is computed ONCE from [latitude]/[longitude]/[altitude] before
     * registering the listener, not per-emission - it doesn't meaningfully change within one
     * recording, and recomputing [GeomagneticField] on every tick would be wasted work.
     * Emits null once (and closes) if no rotation-vector sensor exists.
     */
    fun observeHeadings(
        latitude: Double,
        longitude: Double,
        altitude: Double,
        intervalMs: Long,
    ): Flow<Float?> = callbackFlow {
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sensor == null) {
            trySend(null)
            close()
            return@callbackFlow
        }

        val declination = declinationDegrees(latitude, longitude, altitude)
        val rotationMatrix = FloatArray(9)
        val orientation = FloatArray(3)
        var lastEmittedAt = 0L

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val now = System.currentTimeMillis()
                // registerListener's samplingPeriodUs is only a hint - the OS may deliver
                // events faster than requested (e.g. another listener on the same physical
                // sensor, even this class's own readAveragedAzimuthDegrees(), can cause the
                // shared sensor to report at a faster rate to every registered listener).
                // Without this explicit throttle, a burst of events could grow the samples
                // list far beyond the ~50-per-10s-clip size the upload payload assumes,
                // eventually overflowing WorkManager's Data size limit at submit time.
                if (now - lastEmittedAt < intervalMs) return
                lastEmittedAt = now
                val magneticAzimuthDegrees = rawAzimuthDegrees(event, rotationMatrix, orientation)
                trySend(applyDeclination(magneticAzimuthDegrees, declination))
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        sensorManager.registerListener(listener, sensor, (intervalMs * 1000).toInt())
        awaitClose { sensorManager.unregisterListener(listener) }
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
                samples.add(rawAzimuthDegrees(event, rotationMatrix, orientation))

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

    // rotationMatrix/orientation are reused across calls by both callers below - safe because
    // a single SensorEventListener's onSensorChanged is always invoked serially on one thread,
    // never concurrently.
    private fun rawAzimuthDegrees(event: SensorEvent, rotationMatrix: FloatArray, orientation: FloatArray): Float {
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientation)
        return (Math.toDegrees(orientation[0].toDouble()).toFloat() + 360f) % 360f
    }

    private fun declinationDegrees(latitude: Double, longitude: Double, altitude: Double): Float =
        GeomagneticField(
            latitude.toFloat(),
            longitude.toFloat(),
            altitude.toFloat(),
            System.currentTimeMillis(),
        ).declination

    private fun applyDeclination(magneticAzimuthDegrees: Float, declinationDegrees: Float): Float =
        (magneticAzimuthDegrees + declinationDegrees + 360f) % 360f

    /** Arithmetic mean breaks near the 0/360 wrap-around, so this averages on the unit circle. */
    private fun circularMeanDegrees(samplesDegrees: List<Float>): Float {
        val sinSum = samplesDegrees.sumOf { sin(Math.toRadians(it.toDouble())) }
        val cosSum = samplesDegrees.sumOf { cos(Math.toRadians(it.toDouble())) }
        val meanRadians = atan2(sinSum, cosSum)
        return (Math.toDegrees(meanRadians).toFloat() + 360f) % 360f
    }
}
