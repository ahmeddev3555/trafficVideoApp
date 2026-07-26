package com.trafficwatch.server.reports

import com.trafficwatch.server.auth.User
import com.trafficwatch.server.auth.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import java.math.BigDecimal
import java.nio.file.Files
import java.time.Duration
import java.util.UUID

/**
 * End-to-end proof that a submitted report genuinely leaves `PENDING` on its own, via the
 * real `@Async` [ReportAnalysisJob] wired into [ReportService.submit] - not mocked, unlike
 * `ReportServiceTest`. Boots the full application context (real
 * `com.trafficwatch.server.config.AsyncConfig` executor, real [ReportRepository] against the
 * H2 test database, real [ReportAnalysisJob]) with the analysis delay overridden to ~50ms
 * ([app.analysis.delay-ms]) so the test doesn't wait on the real ~10s default.
 *
 * Waiting for the async flip uses Awaitility's bounded `await().atMost(...).until(...)`
 * poll - not a single fixed `Thread.sleep` guess (slower than necessary and still flaky at
 * the margin) and not an unbounded wait (would hang forever if the async path were ever
 * broken).
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = ["app.analysis.delay-ms=50"])
class ReportAnalysisIntegrationTest @Autowired constructor(
    private val reportService: ReportService,
    private val reportRepository: ReportRepository,
    private val userRepository: UserRepository,
) {

    companion object {
        // Videos submitted via the real ReportService/LocalDiskVideoStorageService during
        // this test are written to an isolated temp directory (rather than the module's
        // real server/storage/videos) so the test never depends on, or pollutes, that
        // directory - registered via @DynamicPropertySource since the path isn't known
        // until test class load time.
        private val tempVideoDir = Files.createTempDirectory("trafficwatch-analysis-it-videos")

        @JvmStatic
        @DynamicPropertySource
        fun overrideStorageDirectory(registry: DynamicPropertyRegistry) {
            registry.add("app.storage.video-directory") { tempVideoDir.toString() }
        }
    }

    private fun authenticateAsNewUser(): UUID {
        val user = userRepository.saveAndFlush(
            User(
                name = "Analysis Test User",
                phoneNumber = "0300${(1_000_000..9_999_999).random()}",
                cnic = "12345-1234567-1",
                email = "analysis-test-${UUID.randomUUID()}@example.com",
                passwordHash = "hashed-password",
            ),
        )
        val userId = requireNotNull(user.id)
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(userId, null, emptyList())
        return userId
    }

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    private fun sampleVideo() = MockMultipartFile("video", "clip.mp4", "video/mp4", byteArrayOf(1, 2, 3))

    private fun submitReport(): UUID {
        authenticateAsNewUser()
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
        return response.reportId
    }

    @Test
    fun `submit returns immediately with PENDING and the report eventually flips to CONFIRMED or REJECTED on its own`() {
        val reportId = submitReport()

        // Proves submit() doesn't block on the (50ms) analysis delay: read straight back
        // from the repository the instant submit() returns, before this test does any
        // waiting of its own. Scheduling the async task and running Thread.sleep(50) both
        // take non-zero time, so the row should still be PENDING here.
        val immediatelyAfterSubmit = reportRepository.findById(reportId).orElseThrow()
        assertThat(immediatelyAfterSubmit.status).isEqualTo(ReportStatus.PENDING)
        val createdAt = immediatelyAfterSubmit.createdAt

        await()
            .atMost(Duration.ofSeconds(5))
            .pollInterval(Duration.ofMillis(25))
            .until {
                reportRepository.findById(reportId).orElseThrow().status != ReportStatus.PENDING
            }

        val finalReport = reportRepository.findById(reportId).orElseThrow()
        assertThat(finalReport.status).isIn(ReportStatus.CONFIRMED, ReportStatus.REJECTED)
        assertThat(finalReport.updatedAt).isAfter(createdAt)
    }

    @Test
    fun `CONFIRMED reports produced by the real async job have non-null license plate and confidence`() {
        // The CONFIRMED/REJECTED split is a genuine 80/20 random draw inside the real
        // ReportAnalysisJob (not stubbed here) - submitting a batch makes the chance of
        // never observing a CONFIRMED outcome astronomically small (0.2^20 ~= 1e-14),
        // without pinning down exactly how many of each this run produces.
        val reportIds = (1..20).map { submitReport() }

        await()
            .atMost(Duration.ofSeconds(5))
            .pollInterval(Duration.ofMillis(25))
            .until {
                reportIds.all { reportRepository.findById(it).orElseThrow().status != ReportStatus.PENDING }
            }

        val finalReports = reportIds.map { reportRepository.findById(it).orElseThrow() }
        val confirmed = finalReports.filter { it.status == ReportStatus.CONFIRMED }
        assertThat(confirmed).isNotEmpty()
        confirmed.forEach { report ->
            assertThat(report.licensePlate).isNotNull()
            assertThat(report.confidence).isNotNull()
            assertThat(report.analysisMessage).isNotNull()
        }
    }
}
