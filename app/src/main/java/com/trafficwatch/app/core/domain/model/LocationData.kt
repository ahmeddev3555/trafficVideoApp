package com.trafficwatch.app.core.domain.model

data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,       // metres
    val altitude: Double,      // metres
    val bearing: Float,        // degrees from north
    val speed: Float,          // m/s
    val capturedAt: Long       // epoch millis — moment recording started
)
