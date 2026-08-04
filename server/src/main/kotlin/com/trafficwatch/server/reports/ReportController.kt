package com.trafficwatch.server.reports

import com.trafficwatch.server.common.CurrentUser
import com.trafficwatch.server.reports.dto.ReportListResponse
import com.trafficwatch.server.reports.dto.ReportStatusResponse
import com.trafficwatch.server.reports.dto.SubmitReportResponse
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.math.BigDecimal
import java.util.UUID

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
        // Absent on submissions from app versions predating compass capture - required =
        // false rather than rejecting the request outright, so ReportAnalysisJob's "no
        // compass heading" rejection path handles it as an analysis outcome instead.
        @RequestParam("compass_heading_degrees", required = false) compassHeadingDegrees: BigDecimal?,
        @RequestParam("location_samples", required = false) locationSamplesJson: String?,
        @RequestParam("rotation_samples", required = false) rotationSamplesJson: String?,
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
            compassHeadingDegrees = compassHeadingDegrees,
            locationSamplesJson = locationSamplesJson,
            rotationSamplesJson = rotationSamplesJson,
        )

    /**
     * `GET /reports/{reportId}/status` - per-user scoped; [ReportService.getStatus] throws
     * `ReportNotFoundException` (mapped to 404 by `GlobalExceptionHandler`) if `reportId`
     * doesn't belong to the authenticated user, or doesn't exist at all.
     */
    @GetMapping("/reports/{reportId}/status")
    fun getReportStatus(@PathVariable reportId: UUID): ReportStatusResponse =
        reportService.getStatus(reportId, CurrentUser.id())

    /**
     * `GET /reports` - `page` is 1-indexed on the wire (client sends `page=1` for the
     * first page); [ReportService.listReports] handles the conversion to Spring Data's
     * 0-indexed `PageRequest`. `status` is an optional filter to a single status value.
     */
    @GetMapping("/reports")
    fun listReports(
        @RequestParam("page", defaultValue = "1") page: Int,
        @RequestParam("page_size", defaultValue = "20") pageSize: Int,
        @RequestParam("status", required = false) status: ReportStatus?,
    ): ReportListResponse =
        reportService.listReports(CurrentUser.id(), page, pageSize, status)

    /**
     * `GET /reports/{reportId}/wrong-way-frame` - same per-user scoping/404 behavior as
     * `GET /reports/{reportId}/status` (see [ReportService.getWrongWayFramePath]). Returns
     * the annotated (red-boxed) frame image as raw JPEG bytes - the first endpoint in this
     * API to serve back binary media.
     */
    @GetMapping("/reports/{reportId}/wrong-way-frame", produces = [MediaType.IMAGE_JPEG_VALUE])
    fun getWrongWayFrame(@PathVariable reportId: UUID): ResponseEntity<Resource> {
        val path = reportService.getWrongWayFramePath(reportId, CurrentUser.id())
        return ResponseEntity.ok()
            .contentType(MediaType.IMAGE_JPEG)
            .body(FileSystemResource(path) as Resource)
    }
}
