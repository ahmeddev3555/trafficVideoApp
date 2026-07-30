package com.trafficwatch.server.reports

import com.trafficwatch.server.reports.exception.InvalidPaginationException
import com.trafficwatch.server.reports.exception.ReportNotFoundException
import com.trafficwatch.server.storage.VideoStorageService
import com.trafficwatch.server.storage.WrongWayFrameStorageService
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.UUID

/**
 * [ReportService.submit] tested against mocked [ReportRepository]/[VideoStorageService] -
 * mirrors AuthServiceTest's style of exercising real service logic against fakes for its
 * collaborators. "Current user" resolution goes through
 * `com.trafficwatch.server.common.CurrentUser`, which reads the [SecurityContextHolder]
 * directly (exactly as [com.trafficwatch.server.auth.JwtAuthFilter] populates it for a
 * real request), so each test seeds/clears that context manually.
 *
 * [submit] now registers [ReportAnalysisJob.analyze] via
 * [TransactionSynchronizationManager.registerSynchronization]'s `afterCommit` callback
 * rather than calling it directly - `registerSynchronization` throws unless a transaction
 * synchronization is active, which this plain (non-Spring-context) test has to set up and
 * tear down itself, mimicking what the real `@Transactional` proxy does in production.
 * [simulateCommit] then plays the role of "the transaction actually committed."
 */
class ReportServiceTest {

    private val reportRepository = mockk<ReportRepository>()
    private val videoStorageService = mockk<VideoStorageService>()
    private val reportAnalysisJob = mockk<ReportAnalysisJob>()
    private val wrongWayFrameStorageService = mockk<WrongWayFrameStorageService>()
    private val reportService = ReportService(
        reportRepository, videoStorageService, reportAnalysisJob, wrongWayFrameStorageService,
    )

    private val currentUserId = UUID.randomUUID()

    @BeforeEach
    fun authenticateAsCurrentUser() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(currentUserId, null, emptyList())
        TransactionSynchronizationManager.initSynchronization()
    }

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }

    /** Simulates the real `@Transactional` proxy's post-commit callback firing. */
    private fun simulateCommit() {
        TransactionSynchronizationManager.getSynchronizations().forEach { it.afterCommit() }
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
        every { reportAnalysisJob.analyze(fixedId) } just runs

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
            compassHeadingDegrees = null,
        )
        simulateCommit()

        verify(exactly = 2) { reportRepository.save(any()) }
        val persisted = savedReport

        assertThat(persisted.status).isEqualTo(ReportStatus.PENDING)
        assertThat(persisted.userId).isEqualTo(currentUserId)
        assertThat(persisted.videoPath).isEqualTo("$fixedId.mp4")

        assertThat(response.reportId).isEqualTo(fixedId)
        assertThat(response.status).isEqualTo(ReportStatus.PENDING)

        verify(exactly = 1) { videoStorageService.store(fixedId, any()) }
        // The analysis job is kicked off with the real generated id, after both saves
        // have succeeded - proves submit() actually wires the real job in rather than
        // leaving Task 9's no-op behavior (report stuck PENDING forever).
        verify(exactly = 1) { reportAnalysisJob.analyze(fixedId) }
    }

    @Test
    fun `submit parses recorded_at leniently as a timezone-less LocalDateTime`() {
        val fixedId = UUID.randomUUID()
        stubSaveAssigningId(fixedId)
        every { videoStorageService.store(any(), any()) } returns "$fixedId.mp4"
        every { reportAnalysisJob.analyze(any()) } just runs

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
            compassHeadingDegrees = null,
        )
        simulateCommit()

        verify(exactly = 2) { reportRepository.save(any()) }

        assertThat(savedReport.recordedAt).isEqualTo(LocalDateTime.of(2026, 7, 25, 14, 30, 45))
    }

    // Reproduces the orphaned-file gap the finding described: the video is written to disk
    // successfully by `store()`, but the second `save()` (persisting the real video path)
    // then fails. `@Transactional` rolls back the DB insert, but never touches the
    // filesystem, so `submit()` itself must delete the just-written file in a catch block -
    // this proves that cleanup call happens with the right path, and that the original
    // exception still propagates rather than being swallowed or replaced.
    @Test
    fun `submit deletes the just-written video and rethrows if the second save fails`() {
        val fixedId = UUID.randomUUID()
        val storedPath = "$fixedId.mp4"
        val dbFailure = RuntimeException("connection drop")

        var saveCallCount = 0
        every { reportRepository.save(any()) } answers {
            saveCallCount++
            val report = firstArg<Report>()
            if (saveCallCount == 1) {
                report.id = fixedId
                report
            } else {
                throw dbFailure
            }
        }
        every { videoStorageService.store(fixedId, any()) } returns storedPath
        every { videoStorageService.delete(storedPath) } just runs

        assertThatThrownBy {
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
                compassHeadingDegrees = null,
            )
        }.isSameAs(dbFailure)
        simulateCommit()

        verify(exactly = 1) { videoStorageService.delete(storedPath) }
        verify(exactly = 2) { reportRepository.save(any()) }
        // The failure happens before the analysis job would even be registered for
        // afterCommit, so it must never fire for a report that was never durably
        // persisted with its real video path.
        verify(exactly = 0) { reportAnalysisJob.analyze(any()) }
    }

    // --- getStatus ---------------------------------------------------------------------

    private fun sampleReport(
        id: UUID,
        userId: UUID,
        status: ReportStatus = ReportStatus.PENDING,
        licensePlate: String? = null,
        confidence: BigDecimal? = null,
        analysisMessage: String? = null,
    ) = Report(
        userId = userId,
        videoPath = "/videos/$id.mp4",
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
        licensePlate = licensePlate,
        confidence = confidence,
        analysisMessage = analysisMessage,
        updatedAt = OffsetDateTime.parse("2026-07-25T10:05:00Z"),
    ).apply { this.id = id }

    @Test
    fun `getStatus maps a report owned by the requester into a ReportStatusResponse`() {
        val reportId = UUID.randomUUID()
        val report = sampleReport(
            id = reportId,
            userId = currentUserId,
            status = ReportStatus.CONFIRMED,
            licensePlate = "LEA-1234",
            confidence = BigDecimal("0.95"),
            analysisMessage = "Plate matched",
        )
        every { reportRepository.findByIdAndUserId(reportId, currentUserId) } returns report

        val response = reportService.getStatus(reportId, currentUserId)

        assertThat(response.reportId).isEqualTo(reportId)
        assertThat(response.status).isEqualTo(ReportStatus.CONFIRMED)
        assertThat(response.licensePlate).isEqualTo("LEA-1234")
        assertThat(response.confidence).isEqualTo(BigDecimal("0.95"))
        assertThat(response.message).isEqualTo("Plate matched")
        assertThat(response.updatedAt).isEqualTo(OffsetDateTime.parse("2026-07-25T10:05:00Z"))
        assertThat(response.hasWrongWayFrame).isFalse()
        assertThat(response.wrongWayConfidence).isNull()
    }

    @Test
    fun `getStatus throws ReportNotFoundException when the report does not belong to the requester`() {
        // findByIdAndUserId returns null both when the id belongs to a different user and
        // when it does not exist at all - either way, getStatus must not leak which case it
        // is, so it always throws the same ReportNotFoundException regardless of the reason.
        val reportId = UUID.randomUUID()
        val strangerId = UUID.randomUUID()
        every { reportRepository.findByIdAndUserId(reportId, strangerId) } returns null

        assertThatThrownBy { reportService.getStatus(reportId, strangerId) }
            .isInstanceOf(ReportNotFoundException::class.java)
    }

    @Test
    fun `getStatus proves per-user scoping - report created for user A is not visible to user B`() {
        val userA = UUID.randomUUID()
        val userB = UUID.randomUUID()
        val reportId = UUID.randomUUID()
        val reportOwnedByA = sampleReport(id = reportId, userId = userA)

        every { reportRepository.findByIdAndUserId(reportId, userA) } returns reportOwnedByA
        every { reportRepository.findByIdAndUserId(reportId, userB) } returns null

        assertThat(reportService.getStatus(reportId, userA).reportId).isEqualTo(reportId)
        assertThatThrownBy { reportService.getStatus(reportId, userB) }
            .isInstanceOf(ReportNotFoundException::class.java)
    }

    // --- listReports --------------------------------------------------------------------

    @Test
    fun `listReports converts a 1-indexed page to a 0-indexed PageRequest`() {
        val pageableSlot = slot<Pageable>()
        every {
            reportRepository.findByUserId(currentUserId, capture(pageableSlot))
        } returns PageImpl(emptyList(), PageRequest.of(0, 20), 0)

        reportService.listReports(currentUserId, page = 1, pageSize = 20, status = null)

        assertThat(pageableSlot.captured.pageNumber).isEqualTo(0)
        assertThat(pageableSlot.captured.pageSize).isEqualTo(20)
    }

    // Guards against a client sending page=0 (or negative): page - 1 would otherwise go
    // negative and reach PageRequest.of directly, which throws a plain
    // IllegalArgumentException with no handler in GlobalExceptionHandler - falling through
    // to Spring Boot's default /error body instead of this API's uniform ApiError shape.
    @Test
    fun `listReports throws InvalidPaginationException when page is less than 1`() {
        assertThatThrownBy { reportService.listReports(currentUserId, page = 0, pageSize = 20, status = null) }
            .isInstanceOf(InvalidPaginationException::class.java)

        assertThatThrownBy { reportService.listReports(currentUserId, page = -1, pageSize = 20, status = null) }
            .isInstanceOf(InvalidPaginationException::class.java)

        verify(exactly = 0) { reportRepository.findByUserId(any(), any<Pageable>()) }
    }

    @Test
    fun `listReports throws InvalidPaginationException when page_size is less than 1`() {
        assertThatThrownBy { reportService.listReports(currentUserId, page = 1, pageSize = 0, status = null) }
            .isInstanceOf(InvalidPaginationException::class.java)

        assertThatThrownBy { reportService.listReports(currentUserId, page = 1, pageSize = -5, status = null) }
            .isInstanceOf(InvalidPaginationException::class.java)

        verify(exactly = 0) { reportRepository.findByUserId(any(), any<Pageable>()) }
    }

    // Without an explicit Sort, page-to-page ordering is DB-heap-dependent - rows can
    // duplicate or be skipped across pages under Postgres. Asserts the service always
    // requests a stable, newest-first order regardless of which repository method it calls.
    @Test
    fun `listReports requests results ordered newest-first by createdAt`() {
        val pageableSlot = slot<Pageable>()
        every {
            reportRepository.findByUserId(currentUserId, capture(pageableSlot))
        } returns PageImpl(emptyList(), PageRequest.of(0, 20), 0)

        reportService.listReports(currentUserId, page = 1, pageSize = 20, status = null)

        val sort = pageableSlot.captured.sort
        assertThat(sort.isSorted).isTrue()
        val order = sort.getOrderFor("createdAt")
        assertThat(order).isNotNull
        assertThat(order!!.direction).isEqualTo(Sort.Direction.DESC)
    }

    @Test
    fun `listReports echoes back the 1-indexed page the client asked for, not the internal 0-indexed value`() {
        every {
            reportRepository.findByUserId(currentUserId, any())
        } returns PageImpl(emptyList(), PageRequest.of(2, 20), 0)

        val response = reportService.listReports(currentUserId, page = 3, pageSize = 20, status = null)

        assertThat(response.page).isEqualTo(3)
    }

    @Test
    fun `listReports without a status filter calls findByUserId and maps total and content`() {
        val report = sampleReport(id = UUID.randomUUID(), userId = currentUserId)
        every {
            reportRepository.findByUserId(currentUserId, any())
        } returns PageImpl(listOf(report), PageRequest.of(0, 20), 1)

        val response = reportService.listReports(currentUserId, page = 1, pageSize = 20, status = null)

        assertThat(response.total).isEqualTo(1)
        assertThat(response.reports).hasSize(1)
        assertThat(response.reports.first().reportId).isEqualTo(report.id)
        verify(exactly = 0) { reportRepository.findByUserIdAndStatus(any(), any(), any()) }
    }

    @Test
    fun `listReports with a status filter calls findByUserIdAndStatus instead of findByUserId`() {
        val report = sampleReport(id = UUID.randomUUID(), userId = currentUserId, status = ReportStatus.CONFIRMED)
        every {
            reportRepository.findByUserIdAndStatus(currentUserId, ReportStatus.CONFIRMED, any())
        } returns PageImpl(listOf(report), PageRequest.of(0, 20), 1)

        val response = reportService.listReports(currentUserId, page = 1, pageSize = 20, status = ReportStatus.CONFIRMED)

        assertThat(response.total).isEqualTo(1)
        assertThat(response.reports.first().status).isEqualTo(ReportStatus.CONFIRMED)
        verify(exactly = 0) { reportRepository.findByUserId(any(), any()) }
    }

    // Every listReports test above only ever exercises a single user, so none of them
    // actually prove per-user isolation the way `getStatus proves per-user scoping` does
    // for the sibling endpoint. This mirrors that test's shape for the list endpoint: two
    // distinct users, each with their own repository stub, proving user B's response never
    // carries user A's report (and vice versa) rather than merely that pagination/filtering
    // works for one user.
    @Test
    fun `listReports proves per-user scoping - user B's list never includes user A's reports`() {
        val userA = UUID.randomUUID()
        val userB = UUID.randomUUID()
        val reportA = sampleReport(id = UUID.randomUUID(), userId = userA)
        val reportB = sampleReport(id = UUID.randomUUID(), userId = userB)

        every {
            reportRepository.findByUserId(userA, any())
        } returns PageImpl(listOf(reportA), PageRequest.of(0, 20), 1)
        every {
            reportRepository.findByUserId(userB, any())
        } returns PageImpl(listOf(reportB), PageRequest.of(0, 20), 1)

        val responseA = reportService.listReports(userA, page = 1, pageSize = 20, status = null)
        val responseB = reportService.listReports(userB, page = 1, pageSize = 20, status = null)

        assertThat(responseA.reports.map { it.reportId }).containsExactly(reportA.id)
        assertThat(responseB.reports.map { it.reportId }).containsExactly(reportB.id)
        assertThat(responseA.reports.map { it.reportId }).doesNotContain(reportB.id)
        assertThat(responseB.reports.map { it.reportId }).doesNotContain(reportA.id)
    }
}
