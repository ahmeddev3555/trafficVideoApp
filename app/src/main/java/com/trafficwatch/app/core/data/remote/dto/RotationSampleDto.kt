package com.trafficwatch.app.core.data.remote.dto

import com.google.gson.annotations.SerializedName
import com.trafficwatch.app.core.domain.model.RotationSample

data class RotationSampleDto(
    @SerializedName("heading_degrees") val headingDegrees: Float,
    // Deliberately named to match the server's Jackson-mapped `capturedAt` property under
    // its global snake_case naming strategy - see the server-side RotationSampleDto.
    @SerializedName("captured_at") val capturedAt: Long,
)

fun RotationSample.toSampleDto(): RotationSampleDto = RotationSampleDto(
    headingDegrees = headingDegrees,
    capturedAt = capturedAt,
)
