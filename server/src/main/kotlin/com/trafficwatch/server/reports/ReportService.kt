package com.trafficwatch.server.reports

import com.trafficwatch.server.common.CurrentUser
import com.trafficwatch.server.reports.dto.SubmitReportResponse
import com.trafficwatch.server.storage.VideoStorageService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Matches the Android client's `recorded_at` format exactly, including its known bug:
 * the client appends a literal `"Z"` without actually converting the timestamp to UTC
 * first. Parsing with `LocalDateTime.parse` (not `Instant`/`OffsetDateTime`, which would
 * enforce real UTC semantics and either reject this format or silently reinterpret it) is
 * deliberate - it treats the trailing `Z` as a literal character, not a UTC offset marker,
 * matching `reports.recorded_at`'s intentionally timezone-less `TIMESTAMP` column.
 */
private val RECORDED_AT_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")

@Service
class ReportService(
    private val reportRepository: ReportRepository,
    private val videoStorageService: VideoStorageService,
) {

    /**
     * Persists a newly submitted report as `PENDING`.
     *
     * The video is stored under the key of the report's own database-generated id, so the
     * report must be saved once first (Hibernate assigns [Report.id] synchronously during
     * that `save()`, before any flush - see `AuthService.register` for the same pattern)
     * to learn that id, then updated with the real video path returned by
     * [VideoStorageService.store]. The whole method is `@Transactional` so a failure in
     * between (e.g. disk write failure) rolls back the first, incomplete insert rather
     * than leaving a `PENDING` row with an empty `video_path` behind.
     *
     * `@Transactional` only covers the database side, though - it cannot undo the
     * filesystem write [VideoStorageService.store] already performed. If the second
     * `save()` (or anything after the video is written) throws, the video file itself would
     * otherwise be orphaned on disk with no DB row ever pointing to it, so that path is
     * explicitly deleted before the original exception is rethrown.
     *
     * Does not invoke any analysis job - a later task wires that call into this method
     * once the real job exists.
     */
    @Transactional
    fun submit(
        video: MultipartFile,
        latitude: BigDecimal,
        longitude: BigDecimal,
        accuracy: BigDecimal,
        altitude: BigDecimal,
        bearing: BigDecimal,
        speed: BigDecimal,
        recordedAt: String,
        durationMs: Long,
        deviceId: String,
    ): SubmitReportResponse {
        val userId = CurrentUser.id()
        val parsedRecordedAt = LocalDateTime.parse(recordedAt, RECORDED_AT_FORMATTER)

        val report = Report(
            userId = userId,
            videoPath = "",
            latitude = latitude,
            longitude = longitude,
            accuracy = accuracy,
            altitude = altitude,
            bearing = bearing,
            speed = speed,
            recordedAt = parsedRecordedAt,
            durationMs = durationMs,
            deviceId = deviceId,
            status = ReportStatus.PENDING,
        )

        val saved = reportRepository.save(report)
        val reportId = requireNotNull(saved.id) { "Saved report must have a generated id" }

        val videoPath = videoStorageService.store(reportId, video)
        try {
            saved.videoPath = videoPath
            reportRepository.save(saved)
        } catch (ex: Exception) {
            videoStorageService.delete(videoPath)
            throw ex
        }

        return SubmitReportResponse(
            reportId = reportId,
            status = saved.status,
            message = "Report submitted successfully",
        )
    }
}
