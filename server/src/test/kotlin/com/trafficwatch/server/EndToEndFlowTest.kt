package com.trafficwatch.server

import com.trafficwatch.server.reports.ReportRepository
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.core.ParameterizedTypeReference
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import java.nio.file.Files
import java.time.Duration
import java.util.UUID
import kotlin.random.Random

/**
 * Genuine black-box HTTP proof that the full pipeline works end to end over the real
 * embedded servlet container (not `MockMvc`, and nothing mocked): register -> login ->
 * submit a report -> poll its status until the real async [com.trafficwatch.server.reports.ReportAnalysisJob]
 * flips it to a terminal state -> confirm it shows up in the user's report list. Also
 * proves the duplicate-phone 409 and the unauthenticated 401 over real HTTP, in the same
 * test run.
 *
 * `webEnvironment = RANDOM_PORT` boots the actual embedded Tomcat + full
 * `ApplicationContext` (real `JwtAuthFilter`, real `ReportAnalysisJob` on its own `@Async`
 * executor, real H2 test database) so [restTemplate]'s requests exercise the identical
 * code path a production Android client would hit - contrast with Task 9/10/11's
 * `@WebMvcTest`/`@SpringBootTest` (mock web environment) slices, which stub out
 * collaborators this test leaves wired for real.
 *
 * This test never sends `compass_heading_degrees` (an older-app-version-shaped request).
 * The real [com.trafficwatch.server.reports.ReportAnalysisJob] still resolves the street
 * and calls video analysis in this case (only candidate direction-scoring is skipped), so
 * this class points `app.video-analysis.base-url` at a closed local port (see
 * [overrideStorageDirectory]'s companion `@DynamicPropertySource`) to make that call fail
 * fast and deterministically, landing on `REJECTED` with a "video analysis service
 * unavailable" message without any WireMock stubbing (contrast with
 * `ReportAnalysisIntegrationTest`, which does stub Nominatim/Overpass/video-analysis to
 * exercise the CONFIRMED path and the specific "compass unavailable" rejection).
 *
 * The test-only H2 database (`jdbc:h2:mem:testdb;...;DB_CLOSE_DELAY=-1`, see
 * `application-test.yml`) is a single named in-memory instance that persists for the life
 * of the JVM running the whole Gradle `test` task, so rows from other test classes in the
 * same run can still be present when this class's tests execute. Every registration below
 * uses a freshly randomized phone/email/cnic (see [uniquePhoneNumber]/[uniqueEmail]/
 * [uniqueCnic]) so this test never collides with leftover data from another test class, or
 * with itself on a repeated run within the same JVM session.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class EndToEndFlowTest @Autowired constructor(
    private val restTemplate: TestRestTemplate,
    private val reportRepository: ReportRepository,
) {

    companion object {
        // As in ReportAnalysisIntegrationTest: reports submitted here go through the real
        // LocalDiskVideoStorageService, so its target directory is redirected to an
        // isolated temp dir for the life of this test class rather than the module's real
        // server/storage/videos.
        private val tempVideoDir = Files.createTempDirectory("trafficwatch-e2e-videos")

        @JvmStatic
        @DynamicPropertySource
        fun overrideStorageDirectory(registry: DynamicPropertyRegistry) {
            registry.add("app.storage.video-directory") { tempVideoDir.toString() }
            // ReportAnalysisJob now resolves the street and calls video analysis even when
            // compass heading is missing (only candidate scoring is skipped) - unlike
            // before, this test's "no compass" submission no longer short-circuits before
            // any network call. Pointed at a closed local port (not WireMock; this test
            // doesn't need canned responses, just deterministic, instant failure) so the
            // video-analysis call fails fast and consistently regardless of whatever the
            // real public Nominatim/Overpass resolve for this coordinate.
            registry.add("app.video-analysis.base-url") { "http://127.0.0.1:1" }
        }
    }

    private val mapType = object : ParameterizedTypeReference<Map<String, Any?>>() {}

    /** Gives the fake in-memory video part a real filename, matching real multipart uploads. */
    private class NamedByteArrayResource(bytes: ByteArray, private val name: String) : ByteArrayResource(bytes) {
        override fun getFilename(): String = name
    }

    private fun uniquePhoneNumber(): String = "03" + String.format("%09d", Random.nextLong(0L, 1_000_000_000L))

    private fun uniqueCnic(): String = String.format("%013d", Random.nextLong(0L, 10_000_000_000_000L))

    private fun uniqueEmail(): String = "e2e-${UUID.randomUUID()}@example.com"

    private fun jsonHeaders(): HttpHeaders = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }

    private fun authHeaders(token: String): HttpHeaders = HttpHeaders().apply { setBearerAuth(token) }

    private fun register(phone: String, email: String, cnic: String, password: String = "supersecret1"): ResponseEntity<Map<String, Any?>> {
        val body = mapOf(
            "name" to "E2E Test User",
            "phone_number" to phone,
            "cnic" to cnic,
            "email" to email,
            "password" to password,
        )
        return restTemplate.exchange("/auth/register", HttpMethod.POST, HttpEntity(body, jsonHeaders()), mapType)
    }

    private fun login(email: String, password: String): ResponseEntity<Map<String, Any?>> {
        val body = mapOf("email" to email, "password" to password)
        return restTemplate.exchange("/auth/login", HttpMethod.POST, HttpEntity(body, jsonHeaders()), mapType)
    }

    private fun submitReport(token: String): ResponseEntity<Map<String, Any?>> {
        val body: MultiValueMap<String, Any> = LinkedMultiValueMap()
        // In-memory fake video bytes - the server doesn't validate video content, just
        // stores it, so a small deterministic ByteArray is sufficient (no real video file).
        body.add("video", NamedByteArrayResource(ByteArray(1024) { it.toByte() }, "clip.mp4"))
        body.add("latitude", "31.520370")
        body.add("longitude", "74.358749")
        body.add("accuracy", "5.00")
        body.add("altitude", "210.50")
        body.add("bearing", "87.30")
        body.add("speed", "12.40")
        // Matches the Android client's (buggy) recorded_at format that ReportService
        // parses leniently: a literal trailing "Z", not a real UTC offset.
        body.add("recorded_at", "2026-07-25T10:15:30Z")
        body.add("duration_ms", "15000")
        body.add("device_id", "device-e2e-test")

        val headers = authHeaders(token)
        headers.contentType = MediaType.MULTIPART_FORM_DATA

        return restTemplate.exchange("/reports", HttpMethod.POST, HttpEntity(body, headers), mapType)
    }

    private fun getStatus(reportId: String, token: String): ResponseEntity<Map<String, Any?>> =
        restTemplate.exchange(
            "/reports/$reportId/status",
            HttpMethod.GET,
            HttpEntity<Void>(authHeaders(token)),
            mapType,
        )

    private fun listReports(token: String): ResponseEntity<Map<String, Any?>> =
        restTemplate.exchange("/reports", HttpMethod.GET, HttpEntity<Void>(authHeaders(token)), mapType)

    @Test
    fun `register, login, submit, poll to a terminal status, and see it in the list - all over real HTTP`() {
        val phone = uniquePhoneNumber()
        val email = uniqueEmail()
        val cnic = uniqueCnic()
        val password = "supersecret1"

        // 1. POST /auth/register - real HTTP call against the running embedded server.
        val registerResponse = register(phone, email, cnic, password)
        assertThat(registerResponse.statusCode).isEqualTo(HttpStatus.CREATED)
        val registerToken = registerResponse.body?.get("token") as? String
        assertThat(registerToken).isNotBlank()

        // 2. POST /auth/login - a genuinely separate HTTP call (not just reusing the
        // register response's token) so login is exercised for real too.
        val loginResponse = login(email, password)
        assertThat(loginResponse.statusCode).isEqualTo(HttpStatus.OK)
        val loginToken = loginResponse.body?.get("token") as? String
        assertThat(loginToken).isNotBlank()
        val token = requireNotNull(loginToken)

        // 3. POST /reports - real multipart/form-data upload with an in-memory fake video.
        val submitResponse = submitReport(token)
        assertThat(submitResponse.statusCode).isEqualTo(HttpStatus.CREATED)
        val submitBody = requireNotNull(submitResponse.body)
        assertThat(submitBody["status"]).isEqualTo("PENDING")
        val reportId = submitBody["report_id"] as String
        assertThat(reportId).isNotBlank()

        // 4. Poll GET /reports/{id}/status until the real async ReportAnalysisJob flips it
        // out of PENDING. Bounded Awaitility poll, matching Task 11's precedent - not a
        // fixed sleep guess, and not an unbounded wait.
        val terminalStatusBody = mutableMapOf<String, Any?>()
        await()
            .atMost(Duration.ofSeconds(5))
            .pollInterval(Duration.ofMillis(50))
            .until {
                val response = getStatus(reportId, token)
                assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
                val current = requireNotNull(response.body)
                terminalStatusBody.putAll(current)
                current["status"] != "PENDING"
            }

        // 5. video-analysis is pointed at a closed port (see the class doc), so the real
        // ReportAnalysisJob deterministically rejects it as insufficient data - not a
        // random draw, and never depends on real external network behavior.
        assertThat(terminalStatusBody["status"]).isEqualTo("REJECTED")
        assertThat(terminalStatusBody["license_plate"]).isNull()
        assertThat(terminalStatusBody["confidence"]).isNull()
        assertThat(terminalStatusBody["message"] as String).startsWith("Video analysis service unavailable")

        // 6. GET /reports (list) - the submitted report shows up for this user.
        val listResponse = listReports(token)
        assertThat(listResponse.statusCode).isEqualTo(HttpStatus.OK)
        @Suppress("UNCHECKED_CAST")
        val reports = requireNotNull(listResponse.body)["reports"] as List<Map<String, Any?>>
        assertThat(reports.map { it["report_id"] }).contains(reportId)
    }

    @Test
    fun `registering with an already-registered phone number returns 409 over real HTTP`() {
        val phone = uniquePhoneNumber()
        val firstEmail = uniqueEmail()
        val cnic = uniqueCnic()

        val firstRegistration = register(phone, firstEmail, cnic)
        assertThat(firstRegistration.statusCode).isEqualTo(HttpStatus.CREATED)

        // Same phone number, different email/cnic - must still collide on the phone
        // number's unique constraint.
        val secondRegistration = register(phone, uniqueEmail(), uniqueCnic())
        assertThat(secondRegistration.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(secondRegistration.body?.get("error")).isEqualTo("DUPLICATE_PHONE_NUMBER")
    }

    @Test
    fun `GET reports with no Authorization header returns 401 over real HTTP`() {
        val response = restTemplate.exchange("/reports", HttpMethod.GET, HttpEntity.EMPTY, mapType)
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `location_samples round-trips to the stored report exactly as submitted`() {
        val phone = uniquePhoneNumber()
        val email = uniqueEmail()
        val cnic = uniqueCnic()
        val password = "supersecret1"

        val registerResponse = register(phone, email, cnic, password)
        val token = requireNotNull(registerResponse.body?.get("token") as? String)

        val body: MultiValueMap<String, Any> = LinkedMultiValueMap()
        body.add("video", NamedByteArrayResource(ByteArray(1024) { it.toByte() }, "clip.mp4"))
        body.add("latitude", "31.520370")
        body.add("longitude", "74.358749")
        body.add("accuracy", "5.00")
        body.add("altitude", "210.50")
        body.add("bearing", "87.30")
        body.add("speed", "12.40")
        body.add("recorded_at", "2026-07-25T10:15:30Z")
        body.add("duration_ms", "15000")
        body.add("device_id", "device-e2e-test")
        body.add(
            "location_samples",
            """[{"latitude":31.520370,"longitude":74.358749,"accuracy":5.0,"altitude":210.5,"bearing":87.3,"speed":12.4,"captured_at":1735814400000}]""",
        )

        val headers = authHeaders(token)
        headers.contentType = MediaType.MULTIPART_FORM_DATA
        val submitResponse = restTemplate.exchange("/reports", HttpMethod.POST, HttpEntity(body, headers), mapType)

        assertThat(submitResponse.statusCode).isEqualTo(HttpStatus.CREATED)
        val reportId = requireNotNull(submitResponse.body?.get("report_id") as? String)

        val stored = reportRepository.findById(java.util.UUID.fromString(reportId)).orElseThrow()
        assertThat(stored.locationSamples).isNotNull()
        assertThat(stored.locationSamples).contains("\"captured_at\":1735814400000")
    }
}
