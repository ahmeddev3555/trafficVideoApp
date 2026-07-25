package com.trafficwatch.server.reports

import com.trafficwatch.server.auth.JwtAuthFilter
import com.trafficwatch.server.auth.JwtService
import com.trafficwatch.server.config.SecurityConfig
import com.trafficwatch.server.reports.dto.SubmitReportResponse
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
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
            reportService.submit(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
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
}
