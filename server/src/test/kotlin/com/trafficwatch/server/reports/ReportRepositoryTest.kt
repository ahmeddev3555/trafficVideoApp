package com.trafficwatch.server.reports

import com.trafficwatch.server.auth.User
import com.trafficwatch.server.auth.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.PageRequest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.UUID

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ReportRepositoryTest @Autowired constructor(
    private val reportRepository: ReportRepository,
    private val userRepository: UserRepository,
    private val jdbcTemplate: JdbcTemplate,
) {

    private fun newUser(phoneNumber: String, email: String): User =
        userRepository.saveAndFlush(
            User(
                name = "Test User",
                phoneNumber = phoneNumber,
                cnic = "12345-1234567-1",
                email = email,
                passwordHash = "hashed-password",
            ),
        )

    private fun newReport(userId: UUID, status: ReportStatus = ReportStatus.PENDING) = Report(
        userId = userId,
        videoPath = "/videos/abc.mp4",
        latitude = BigDecimal("31.520370"),
        longitude = BigDecimal("74.358749"),
        accuracy = BigDecimal("5.00"),
        altitude = BigDecimal("210.50"),
        bearing = BigDecimal("87.30"),
        speed = BigDecimal("12.40"),
        recordedAt = LocalDateTime.of(2026, 7, 25, 10, 0, 0),
        durationMs = 15000L,
        deviceId = "device-123",
        status = status,
    )

    @Test
    fun `saved report can be found by id with all fields intact`() {
        val user = newUser("03001234567", "first@example.com")
        val report = newReport(user.id!!)

        val saved = reportRepository.saveAndFlush(report)
        val found = reportRepository.findById(saved.id!!).orElse(null)

        assertThat(found).isNotNull
        assertThat(found.userId).isEqualTo(user.id)
        assertThat(found.status).isEqualTo(ReportStatus.PENDING)
        assertThat(found.videoPath).isEqualTo("/videos/abc.mp4")
        assertThat(found.licensePlate).isNull()
        assertThat(found.confidence).isNull()
        assertThat(found.analysisMessage).isNull()
    }

    @Test
    fun `findByUserId returns only that user's reports`() {
        val userA = newUser("03001111111", "a@example.com")
        val userB = newUser("03002222222", "b@example.com")
        reportRepository.saveAndFlush(newReport(userA.id!!))
        reportRepository.saveAndFlush(newReport(userA.id!!))
        reportRepository.saveAndFlush(newReport(userB.id!!))

        val results = reportRepository.findByUserId(userA.id!!)

        assertThat(results).hasSize(2)
        assertThat(results).allMatch { it.userId == userA.id }
    }

    @Test
    fun `findByUserIdAndStatus filters by both user and status`() {
        val user = newUser("03003333333", "c@example.com")
        reportRepository.saveAndFlush(newReport(user.id!!, ReportStatus.PENDING))
        reportRepository.saveAndFlush(newReport(user.id!!, ReportStatus.CONFIRMED))

        val pending = reportRepository.findByUserIdAndStatus(user.id!!, ReportStatus.PENDING)

        assertThat(pending).hasSize(1)
        assertThat(pending.first().status).isEqualTo(ReportStatus.PENDING)
    }

    @Test
    fun `findByIdAndUserId returns report only for the owning user, null for another user`() {
        val owner = newUser("03004444444", "d@example.com")
        val stranger = newUser("03005555555", "e@example.com")
        val saved = reportRepository.saveAndFlush(newReport(owner.id!!))

        assertThat(reportRepository.findByIdAndUserId(saved.id!!, owner.id!!)).isNotNull
        assertThat(reportRepository.findByIdAndUserId(saved.id!!, stranger.id!!)).isNull()
    }

    @Test
    fun `findByUserId with a Pageable returns a Page with correct total and page size`() {
        val user = newUser("03007777777", "g@example.com")
        repeat(3) { reportRepository.saveAndFlush(newReport(user.id!!)) }

        val firstPage = reportRepository.findByUserId(user.id!!, PageRequest.of(0, 2))

        assertThat(firstPage.totalElements).isEqualTo(3)
        assertThat(firstPage.content).hasSize(2)
        assertThat(firstPage.totalPages).isEqualTo(2)

        val secondPage = reportRepository.findByUserId(user.id!!, PageRequest.of(1, 2))
        assertThat(secondPage.content).hasSize(1)
    }

    @Test
    fun `findByUserIdAndStatus with a Pageable filters by status and paginates`() {
        val user = newUser("03008888888", "h@example.com")
        reportRepository.saveAndFlush(newReport(user.id!!, ReportStatus.PENDING))
        reportRepository.saveAndFlush(newReport(user.id!!, ReportStatus.CONFIRMED))
        reportRepository.saveAndFlush(newReport(user.id!!, ReportStatus.CONFIRMED))

        val confirmedPage = reportRepository.findByUserIdAndStatus(
            user.id!!,
            ReportStatus.CONFIRMED,
            PageRequest.of(0, 10),
        )

        assertThat(confirmedPage.totalElements).isEqualTo(2)
        assertThat(confirmedPage.content).allMatch { it.status == ReportStatus.CONFIRMED }
    }

    @Test
    fun `database CHECK constraint rejects an invalid status value via raw insert`() {
        val user = newUser("03006666666", "f@example.com")
        val now = OffsetDateTime.now()

        assertThatThrownBy {
            jdbcTemplate.update(
                """
                INSERT INTO reports (
                    id, user_id, video_path, latitude, longitude, accuracy, altitude,
                    bearing, speed, recorded_at, duration_ms, device_id, status,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                UUID.randomUUID(),
                user.id,
                "/videos/bogus.mp4",
                BigDecimal("1.0"),
                BigDecimal("1.0"),
                BigDecimal("1.0"),
                BigDecimal("1.0"),
                BigDecimal("1.0"),
                BigDecimal("1.0"),
                LocalDateTime.now(),
                1000L,
                "device-x",
                "BOGUS",
                now,
                now,
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }
}
