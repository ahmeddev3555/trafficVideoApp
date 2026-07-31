package com.trafficwatch.server.reports

import com.fasterxml.jackson.databind.ObjectMapper
import com.trafficwatch.server.common.CurrentUser
import com.trafficwatch.server.reports.dto.ReportListResponse
import com.trafficwatch.server.reports.dto.ReportStatusResponse
import com.trafficwatch.server.reports.dto.SubmitReportResponse
import com.trafficwatch.server.reports.exception.InvalidPaginationException
import com.trafficwatch.server.reports.exception.ReportNotFoundException
import com.trafficwatch.server.storage.VideoStorageService
import com.trafficwatch.server.storage.WrongWayFrameStorageService
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.multipart.MultipartFile
import java.math.BigDecimal
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

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
    private val reportAnalysisJob: ReportAnalysisJob,
    private val wrongWayFrameStorageService: WrongWayFrameStorageService,
    private val objectMapper: ObjectMapper,
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
     * Once the report is safely persisted, [ReportAnalysisJob.analyze] is scheduled via
     * [TransactionSynchronizationManager.registerSynchronization]'s `afterCommit` callback,
     * rather than called directly here - this method still runs inside its own open
     * `@Transactional` block, so a direct call would race the commit (the analysis job's
     * `findById` could run before this transaction's insert is visible to it). `afterCommit`
     * makes that race structurally impossible instead of merely improbable: the callback
     * only fires once the transaction has actually committed. A failure that rolls back the
     * transaction or leaves the video orphaned never reaches this registration at all, so a
     * stray analysis job never runs against a report that doesn't durably exist.
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
        compassHeadingDegrees: BigDecimal?,
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
            compassHeadingDegrees = compassHeadingDegrees,
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

        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() {
                    reportAnalysisJob.analyze(reportId)
                }
            },
        )

        return SubmitReportResponse(
            reportId = reportId,
            status = saved.status,
            message = "Report submitted successfully",
        )
    }

    /**
     * Backs `GET /reports/{reportId}/status`. Uses [ReportRepository.findByIdAndUserId] -
     * the same per-user scoping guard `submit()`'s sibling endpoints rely on - so a report
     * id that belongs to a different user is indistinguishable from one that doesn't exist
     * at all: both throw [ReportNotFoundException], which `GlobalExceptionHandler` maps to
     * a 404, never a 403 or another user's data.
     */
    fun getStatus(reportId: UUID, currentUserId: UUID): ReportStatusResponse {
        val report = reportRepository.findByIdAndUserId(reportId, currentUserId)
            ?: throw ReportNotFoundException(reportId)
        return report.toStatusResponse()
    }

    /**
     * Backs `GET /reports/{reportId}/wrong-way-frame`. Same per-user scoping as [getStatus]
     * - and the same [ReportNotFoundException] (mapped to a 404) when the report has no
     * stored frame at all (old report predating this feature, or annotation/storage failed)
     * - the caller has no way to distinguish "wrong owner", "doesn't exist", and "no frame
     * yet", by design.
     */
    fun getWrongWayFramePath(reportId: UUID, currentUserId: UUID): Path {
        val report = reportRepository.findByIdAndUserId(reportId, currentUserId)
            ?: throw ReportNotFoundException(reportId)
        val framePath = report.wrongWayFramePath ?: throw ReportNotFoundException(reportId)
        return wrongWayFrameStorageService.resolve(framePath)
    }

    /**
     * Backs `GET /reports`. `page` is the 1-indexed page number as sent by the Android
     * client; Spring Data's [PageRequest] is 0-indexed, so `page - 1` is what's actually
     * passed to the repository. [ReportListResponse.page] echoes back the original
     * 1-indexed `page` argument, not the internal 0-indexed value, so the client sees back
     * exactly what it asked for.
     *
     * Both `page` and `pageSize` must be at least 1: `page - 1` going negative (e.g.
     * `page=0`) would otherwise reach [PageRequest.of] directly, which throws a plain
     * `IllegalArgumentException` with no handler in `GlobalExceptionHandler` - falling
     * through to Spring Boot's default `/error` body instead of this API's uniform
     * `ApiError` shape. [InvalidPaginationException] is thrown instead so it can be mapped
     * to a proper 400 there.
     *
     * Results are explicitly ordered newest-first (`createdAt` descending) rather than
     * left to whatever order the database happens to return rows in - without an explicit
     * `Sort`, page-to-page ordering is DB-heap-dependent and rows can duplicate or be
     * skipped across pages.
     */
    fun listReports(currentUserId: UUID, page: Int, pageSize: Int, status: ReportStatus?): ReportListResponse {
        if (page < 1) {
            throw InvalidPaginationException("page must be at least 1, got $page")
        }
        if (pageSize < 1) {
            throw InvalidPaginationException("page_size must be at least 1, got $pageSize")
        }

        val pageable = PageRequest.of(page - 1, pageSize, Sort.by(Sort.Direction.DESC, "createdAt"))
        val resultPage = if (status != null) {
            reportRepository.findByUserIdAndStatus(currentUserId, status, pageable)
        } else {
            reportRepository.findByUserId(currentUserId, pageable)
        }

        return ReportListResponse(
            reports = resultPage.content.map { it.toStatusResponse() },
            total = resultPage.totalElements,
            page = page,
        )
    }

    private fun Report.toStatusResponse(): ReportStatusResponse {
        val reportId = requireNotNull(id) { "Report must have a generated id" }
        return ReportStatusResponse(
            reportId = reportId,
            status = status,
            licensePlate = licensePlate,
            confidence = confidence,
            message = analysisMessage,
            updatedAt = updatedAt,
            streetName = streetName,
            hasWrongWayFrame = wrongWayFramePath != null,
            wrongWayConfidence = wrongWayConfidence,
            evidenceBreakdown = directionEvidence?.let {
                try {
                    objectMapper.readTree(it)
                } catch (ex: Exception) {
                    null
                }
            },
        )
    }
}
