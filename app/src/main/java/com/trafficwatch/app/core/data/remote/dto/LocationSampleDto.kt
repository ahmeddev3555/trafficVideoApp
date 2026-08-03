package com.trafficwatch.app.core.data.remote.dto

import com.google.gson.annotations.SerializedName
import com.trafficwatch.app.core.domain.model.LocationData

data class LocationSampleDto(
    @SerializedName("latitude") val latitude: Double,
    @SerializedName("longitude") val longitude: Double,
    @SerializedName("accuracy") val accuracy: Float,
    @SerializedName("altitude") val altitude: Double,
    @SerializedName("bearing") val bearing: Float,
    @SerializedName("speed") val speed: Float,
    // Deliberately named to match the server's Jackson-mapped `capturedAt` property under
    // its global snake_case naming strategy - see the server-side LocationSampleDto.
    @SerializedName("captured_at") val capturedAt: Long,
)

fun LocationData.toSampleDto(): LocationSampleDto = LocationSampleDto(
    latitude = latitude,
    longitude = longitude,
    accuracy = accuracy,
    altitude = altitude,
    bearing = bearing,
    speed = speed,
    capturedAt = capturedAt,
)
