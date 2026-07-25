package com.trafficwatch.server.reports

import com.trafficwatch.server.reports.dto.SubmitReportResponse
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.math.BigDecimal

/**
 * `POST /reports` - accepts a `multipart/form-data` request (not JSON): the video as a
 * file part plus nine scalar text parts. Individual `@RequestParam`s are used instead of a
 * combined `@ModelAttribute` - simpler and more explicit for a mixed multipart request with
 * this many scalar fields. Delegates straight to [ReportService.submit].
 */
@RestController
class ReportController(
    private val reportService: ReportService,
) {

    @PostMapping("/reports", consumes = ["multipart/form-data"])
    @ResponseStatus(HttpStatus.CREATED)
    fun submitReport(
        @RequestPart("video") video: MultipartFile,
        @RequestParam("latitude") latitude: BigDecimal,
        @RequestParam("longitude") longitude: BigDecimal,
        @RequestParam("accuracy") accuracy: BigDecimal,
        @RequestParam("altitude") altitude: BigDecimal,
        @RequestParam("bearing") bearing: BigDecimal,
        @RequestParam("speed") speed: BigDecimal,
        @RequestParam("recorded_at") recordedAt: String,
        @RequestParam("duration_ms") durationMs: Long,
        @RequestParam("device_id") deviceId: String,
    ): SubmitReportResponse =
        reportService.submit(
            video = video,
            latitude = latitude,
            longitude = longitude,
            accuracy = accuracy,
            altitude = altitude,
            bearing = bearing,
            speed = speed,
            recordedAt = recordedAt,
            durationMs = durationMs,
            deviceId = deviceId,
        )
}
