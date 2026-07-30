package com.trafficwatch.app.core.data.remote.dto

import com.google.gson.annotations.SerializedName

data class SubmitReportResponse(
    @SerializedName("report_id") val reportId: String,
    @SerializedName("status") val status: String,
    @SerializedName("message") val message: String?
)

data class ReportStatusResponse(
    @SerializedName("report_id") val reportId: String,
    @SerializedName("status") val status: String,
    @SerializedName("license_plate") val licensePlate: String?,
    @SerializedName("confidence") val confidence: Float?,
    @SerializedName("message") val message: String?,
    @SerializedName("updated_at") val updatedAt: String,
    @SerializedName("has_wrong_way_frame") val hasWrongWayFrame: Boolean,
    @SerializedName("wrong_way_confidence") val wrongWayConfidence: Float?
)

data class ReportListResponse(
    @SerializedName("reports") val reports: List<ReportStatusResponse>,
    @SerializedName("total") val total: Int,
    @SerializedName("page") val page: Int
)
