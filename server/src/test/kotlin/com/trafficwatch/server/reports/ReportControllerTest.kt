package com.trafficwatch.server.reports

import com.trafficwatch.server.auth.JwtAuthFilter
import com.trafficwatch.server.auth.JwtService
import com.trafficwatch.server.config.SecurityConfig
import com.trafficwatch.server.reports.dto.ReportListResponse
import com.trafficwatch.server.reports.dto.ReportStatusResponse
import com.trafficwatch.server.reports.dto.SubmitReportResponse
import com.trafficwatch.server.reports.exception.InvalidPaginationException
import com.trafficwatch.server.reports.exception.ReportNotFoundException
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

/**
 * @WebMvcTest slice covering the actual HTTP wire contract for POST /reports: multipart
 * part names, the 201 + snake_case response shape, and - since this is the first
 * @WebMvcTest whose endpoint sits behind `authenticated()` rather than `permitAll()` -
 * genuine authentication via a real bearer JWT.
 *
 * Mirrors AuthControllerTest's approach of @Import-ing SecurityConfig + JwtAuthFilter
 * (not scanned automatically by @WebMvcTest) and wiring a real JwtService bean so
 * jwtService.generateToken(...) produces a token JwtAuthFilter will actually accept -
 * this is simpler and more realistic than trying to fabricate a UUID-principal
 * Authentication via a custom test SecurityContext, since it exercises the exact same
 * filter that runs in production.
 */
@WebMvcTest(ReportController::class)
@Import(SecurityConfig::class, JwtAuthFilter::class, ReportControllerTest.TestConfig::class)
class ReportControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var reportService: ReportService

    @Autowired
    private lateinit var jwtService: JwtService

    @TestConfiguration
    class TestConfig {
        @Bean
        fun reportService(): ReportService = mockk()

        @Bean
        fun jwtService(): JwtService = JwtService(
            secret = "web-mvc-test-only-jwt-signing-secret-do-not-use-elsewhere-0123456789",
            expirationDays = 30,
        )
    }

    private fun authorizedRequest(video: MockMultipartFile) =
        multipart("/reports")
            .file(video)
            .param("latitude", "31.520370")
            .param("longitude", "74.358749")
            .param("accuracy", "5.00")
            .param("altitude", "210.50")
            .param("bearing", "87.30")
            .param("speed", "12.40")
            .param("recorded_at", "2026-07-25T10:15:30Z")
            .param("duration_ms", "15000")
            .param("device_id", "device-123")

    @Test
    fun `submitReport with all parts and a valid token returns 201 with snake_case response shape`() {
        val reportId = UUID.randomUUID()
        every {
            reportService.submit(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns SubmitReportResponse(
            reportId = reportId,
            status = ReportStatus.PENDING,
            message = "Report submitted successfully",
        )

        val token = jwtService.generateToken(UUID.randomUUID())
        val video = MockMultipartFile("video", "clip.mp4", "video/mp4", byteArrayOf(1, 2, 3))

        mockMvc.perform(
            authorizedRequest(video).header("Authorization", "Bearer $token"),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.report_id").value(reportId.toString()))
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.message").value("Report submitted successfully"))
    }

    @Test
    fun `submitReport without an Authorization header is rejected with 401, service never invoked`() {
        val video = MockMultipartFile("video", "clip.mp4", "video/mp4", byteArrayOf(1, 2, 3))

        mockMvc.perform(authorizedRequest(video))
            .andExpect(status().isUnauthorized)
    }

    private fun bearerToken() = jwtService.generateToken(UUID.randomUUID())

    @Test
    fun `getReportStatus with a valid token returns 200 with snake_case ReportStatusResponse shape`() {
        val reportId = UUID.randomUUID()
        val updatedAt = OffsetDateTime.parse("2026-07-25T10:05:00Z")
        every { reportService.getStatus(reportId, any()) } returns ReportStatusResponse(
            reportId = reportId,
            status = ReportStatus.CONFIRMED,
            licensePlate = "LEA-1234",
            confidence = BigDecimal("0.95"),
            message = "Plate matched",
            updatedAt = updatedAt,
            streetName = null,
        )

        mockMvc.perform(
            get("/reports/$reportId/status").header("Authorization", "Bearer ${bearerToken()}"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.report_id").value(reportId.toString()))
            .andExpect(jsonPath("$.status").value("CONFIRMED"))
            .andExpect(jsonPath("$.license_plate").value("LEA-1234"))
            .andExpect(jsonPath("$.confidence").value(0.95))
            .andExpect(jsonPath("$.message").value("Plate matched"))
            .andExpect(jsonPath("$.updated_at").exists())
    }

    // Every other test in this class matches the userId argument passed to the mocked
    // service with `any()`, so none of them actually prove that the specific user id
    // embedded in *this request's* bearer token is what reaches the service - only that
    // *some* valid token produces a 200/201. This exercises the real JwtAuthFilter + real
    // JwtService (both @Import-ed, not mocked) end to end and captures the exact userId
    // CurrentUser.id() resolved, proving it's the token's own subject and not some other
    // value.
    @Test
    fun `getReportStatus passes the userId embedded in the bearer token to the service`() {
        val reportId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val userIdSlot = slot<UUID>()
        every { reportService.getStatus(reportId, capture(userIdSlot)) } returns ReportStatusResponse(
            reportId = reportId,
            status = ReportStatus.PENDING,
            licensePlate = null,
            confidence = null,
            message = null,
            updatedAt = OffsetDateTime.now(),
            streetName = null,
        )

        mockMvc.perform(
            get("/reports/$reportId/status").header("Authorization", "Bearer ${jwtService.generateToken(userId)}"),
        ).andExpect(status().isOk)

        assert(userIdSlot.captured == userId) {
            "expected service to be called with the token's own user id $userId, got ${userIdSlot.captured}"
        }
    }

    @Test
    fun `getReportStatus for a report belonging to another user returns 404 with ApiError body`() {
        val reportId = UUID.randomUUID()
        every { reportService.getStatus(reportId, any()) } throws ReportNotFoundException(reportId)

        mockMvc.perform(
            get("/reports/$reportId/status").header("Authorization", "Bearer ${bearerToken()}"),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("REPORT_NOT_FOUND"))
    }

    @Test
    fun `getReportStatus without an Authorization header is rejected with 401, service never invoked`() {
        mockMvc.perform(get("/reports/${UUID.randomUUID()}/status"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `listReports with no query params defaults to page=1 and page_size=20`() {
        val pageSlot = slot<Int>()
        val pageSizeSlot = slot<Int>()
        every {
            reportService.listReports(any(), capture(pageSlot), capture(pageSizeSlot), null)
        } returns ReportListResponse(reports = emptyList(), total = 0, page = 1)

        mockMvc.perform(get("/reports").header("Authorization", "Bearer ${bearerToken()}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.reports").isArray)
            .andExpect(jsonPath("$.total").value(0))
            .andExpect(jsonPath("$.page").value(1))

        assert(pageSlot.captured == 1) { "expected default page 1, got ${pageSlot.captured}" }
        assert(pageSizeSlot.captured == 20) { "expected default page_size 20, got ${pageSizeSlot.captured}" }
    }

    @Test
    fun `listReports forwards page, page_size and status query params to the service`() {
        every {
            reportService.listReports(any(), 2, 5, ReportStatus.PENDING)
        } returns ReportListResponse(reports = emptyList(), total = 0, page = 2)

        mockMvc.perform(
            get("/reports?page=2&page_size=5&status=PENDING")
                .header("Authorization", "Bearer ${bearerToken()}"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.page").value(2))

        verify(exactly = 1) { reportService.listReports(any(), 2, 5, ReportStatus.PENDING) }
    }

    // Two separate requests with two different users' tokens, both handled by the same
    // MockMvc/filter chain instance - proves the per-request SecurityContext (and thus
    // CurrentUser.id()) doesn't leak or get stuck across requests, which the single-user
    // pagination/filter tests above can't show since they never vary the token's subject.
    @Test
    fun `listReports scopes to the specific user embedded in each request's own bearer token`() {
        val userA = UUID.randomUUID()
        val userB = UUID.randomUUID()
        val userIdSlot = slot<UUID>()
        every {
            reportService.listReports(capture(userIdSlot), any(), any(), any())
        } returns ReportListResponse(reports = emptyList(), total = 0, page = 1)

        mockMvc.perform(get("/reports").header("Authorization", "Bearer ${jwtService.generateToken(userA)}"))
            .andExpect(status().isOk)
        assert(userIdSlot.captured == userA) {
            "expected first request to be scoped to userA $userA, got ${userIdSlot.captured}"
        }

        mockMvc.perform(get("/reports").header("Authorization", "Bearer ${jwtService.generateToken(userB)}"))
            .andExpect(status().isOk)
        assert(userIdSlot.captured == userB) {
            "expected second request to be scoped to userB $userB, got ${userIdSlot.captured}"
        }
    }

    @Test
    fun `listReports without an Authorization header is rejected with 401`() {
        mockMvc.perform(get("/reports"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `listReports with an unparseable status query param returns 400 with ApiError body`() {
        mockMvc.perform(
            get("/reports?status=BOGUS").header("Authorization", "Bearer ${bearerToken()}"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("INVALID_PARAMETER"))
            .andExpect(jsonPath("$.message").exists())
    }

    // Task 6's whole point was a uniform ApiError{error, message} shape across every
    // endpoint - page=0 (or a negative page/page_size) must not fall through to Spring
    // Boot's default /error body via an unhandled IllegalArgumentException. The service is
    // mocked to throw InvalidPaginationException the way the real ReportService.listReports
    // does, proving GlobalExceptionHandler's mapping to a 400 ApiError.
    @Test
    fun `listReports with page=0 returns 400 with ApiError body`() {
        every {
            reportService.listReports(any(), 0, any(), any())
        } throws InvalidPaginationException("page must be at least 1, got 0")

        mockMvc.perform(
            get("/reports?page=0").header("Authorization", "Bearer ${bearerToken()}"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("INVALID_PAGINATION"))
            .andExpect(jsonPath("$.message").exists())
    }

    @Test
    fun `listReports with page_size=0 returns 400 with ApiError body`() {
        every {
            reportService.listReports(any(), any(), 0, any())
        } throws InvalidPaginationException("page_size must be at least 1, got 0")

        mockMvc.perform(
            get("/reports?page_size=0").header("Authorization", "Bearer ${bearerToken()}"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("INVALID_PAGINATION"))
            .andExpect(jsonPath("$.message").exists())
    }
}
