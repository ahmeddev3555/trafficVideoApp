package com.trafficwatch.app.core.domain.model

data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,       // metres
    val altitude: Double,      // metres
    val bearing: Float,        // degrees from north - device direction-of-travel, not camera heading
    val speed: Float,          // m/s
    val capturedAt: Long,      // epoch millis — moment recording started
    // True-north compass heading the camera was pointed at recording start - null if no
    // rotation-vector sensor exists or the read timed out. See CompassProvider.
    val compassHeadingDegrees: Float? = null
)
