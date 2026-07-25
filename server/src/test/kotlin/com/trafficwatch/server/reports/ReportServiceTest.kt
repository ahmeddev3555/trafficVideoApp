package com.trafficwatch.server.reports

import com.trafficwatch.server.storage.VideoStorageService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

/**
 * [ReportService.submit] tested against mocked [ReportRepository]/[VideoStorageService] -
 * mirrors AuthServiceTest's style of exercising real service logic against fakes for its
 * collaborators. "Current user" resolution goes through
 * `com.trafficwatch.server.common.CurrentUser`, which reads the [SecurityContextHolder]
 * directly (exactly as [com.trafficwatch.server.auth.JwtAuthFilter] populates it for a
 * real request), so each test seeds/clears that context manually.
 */
class ReportServiceTest {

    private val reportRepository = mockk<ReportRepository>()
    private val videoStorageService = mockk<VideoStorageService>()
    private val reportService = ReportService(reportRepository, videoStorageService)

    private val currentUserId = UUID.randomUUID()

    @BeforeEach
    fun authenticateAsCurrentUser() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(currentUserId, null, emptyList())
    }

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    // ReportService.submit() saves the report twice (once to obtain the generated id,
    // once more after the video path is known) - both calls mutate and return the same
    // in-memory Report instance, so a single mutable reference (rather than mockk's
    // `slot()`, which errors when a capturing verify block matches more than one
    // invocation) is enough to inspect what was ultimately persisted.
    private lateinit var savedReport: Report

    private fun stubSaveAssigningId(fixedId: UUID) {
        every { reportRepository.save(any()) } answers {
            val report = firstArg<Report>()
            if (report.id == null) {
                report.id = fixedId
            }
            savedReport = report
            report
        }
    }

    private fun sampleVideo() = MockMultipartFile("video", "clip.mp4", "video/mp4", byteArrayOf(1, 2, 3))

    @Test
    fun `submit saves report as PENDING for the authenticated user with the stored video path`() {
        val fixedId = UUID.randomUUID()
        stubSaveAssigningId(fixedId)
        every { videoStorageService.store(fixedId, any()) } returns "$fixedId.mp4"

        val response = reportService.submit(
            video = sampleVideo(),
            latitude = BigDecimal("31.520370"),
            longitude = BigDecimal("74.358749"),
            accuracy = BigDecimal("5.00"),
            altitude = BigDecimal("210.50"),
            bearing = BigDecimal("87.30"),
            speed = BigDecimal("12.40"),
            recordedAt = "2026-07-25T10:15:30Z",
            durationMs = 15000L,
            deviceId = "device-123",
        )

        verify(exactly = 2) { reportRepository.save(any()) }
        val persisted = savedReport

        assertThat(persisted.status).isEqualTo(ReportStatus.PENDING)
        assertThat(persisted.userId).isEqualTo(currentUserId)
        assertThat(persisted.videoPath).isEqualTo("$fixedId.mp4")

        assertThat(response.reportId).isEqualTo(fixedId)
        assertThat(response.status).isEqualTo(ReportStatus.PENDING)

        verify(exactly = 1) { videoStorageService.store(fixedId, any()) }
    }

    @Test
    fun `submit parses recorded_at leniently as a timezone-less LocalDateTime`() {
        val fixedId = UUID.randomUUID()
        stubSaveAssigningId(fixedId)
        every { videoStorageService.store(any(), any()) } returns "$fixedId.mp4"

        reportService.submit(
            video = sampleVideo(),
            latitude = BigDecimal.ONE,
            longitude = BigDecimal.ONE,
            accuracy = BigDecimal.ONE,
            altitude = BigDecimal.ONE,
            bearing = BigDecimal.ONE,
            speed = BigDecimal.ONE,
            recordedAt = "2026-07-25T14:30:45Z",
            durationMs = 1000L,
            deviceId = "device-x",
        )

        verify(exactly = 2) { reportRepository.save(any()) }

        assertThat(savedReport.recordedAt).isEqualTo(LocalDateTime.of(2026, 7, 25, 14, 30, 45))
    }
}
