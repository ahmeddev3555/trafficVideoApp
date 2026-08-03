package com.trafficwatch.app.core.domain.model

data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,       // metres
    val altitude: Double,      // metres
    val bearing: Float,        // degrees from north - device direction-of-travel, not camera heading
    val speed: Float,          // m/s
    // epoch millis. For the single recording-start snapshot (snapshotLocation), this is the
    // moment recording started. For continuous per-second capture (locationSamples), each
    // instance's capturedAt is instead the moment that particular sample was taken - not
    // necessarily recording start.
    val capturedAt: Long,
    // True-north compass heading the camera was pointed at recording start - null if no
    // rotation-vector sensor exists or the read timed out. See CompassProvider.
    val compassHeadingDegrees: Float? = null
)
