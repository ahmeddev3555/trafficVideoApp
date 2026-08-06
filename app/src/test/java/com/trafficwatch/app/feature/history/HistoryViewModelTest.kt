package com.trafficwatch.app.feature.history

import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.cash.turbine.test
import com.trafficwatch.app.core.data.repository.AuthRepository
import com.trafficwatch.app.core.data.repository.ReportRepository
import com.trafficwatch.app.core.domain.model.LocationData
import com.trafficwatch.app.core.domain.model.Report
import com.trafficwatch.app.core.domain.model.ReportStatus
import com.trafficwatch.app.core.domain.usecase.GetReportStatusUseCase
import com.trafficwatch.app.core.domain.usecase.RetryUploadUseCase
import com.trafficwatch.app.feature.upload.UploadWorker
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val reportRepository = mockk<ReportRepository>()
    private val authRepository = mockk<AuthRepository>()
    private val getReportStatusUseCase = mockk<GetReportStatusUseCase>()
    private val retryUploadUseCase = mockk<RetryUploadUseCase>()
    private val workManager = mockk<WorkManager>()

    private val location = LocationData(31.5, 74.3, 5f, 200.0, 90f, 10f, 1000L)

    private fun report(id: String, status: ReportStatus) = Report(
        id = id,
        videoPath = "/tmp/$id.mp4",
        location = location,
        recordingStartedAt = 1000L,
        durationMs = 8000L,
        fileSizeBytes = 1_000_000L,
        status = status,
        createdAt = 1000L,
        updatedAt = 1000L
    )

    private fun workInfo(bytesUploaded: Long, totalBytes: Long, bytesPerSecond: Long): WorkInfo {
        val progress = Data.Builder()
            .putInt(UploadWorker.KEY_PROGRESS, ((bytesUploaded * 100) / totalBytes).toInt())
            .putLong(UploadWorker.KEY_BYTES_UPLOADED, bytesUploaded)
            .putLong(UploadWorker.KEY_TOTAL_BYTES, totalBytes)
            .putLong(UploadWorker.KEY_BYTES_PER_SECOND, bytesPerSecond)
            .build()
        return mockk<WorkInfo> {
            every { this@mockk.progress } returns progress
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { getReportStatusUseCase() } returns emptyFlow()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // uploadProgress is stateIn(..., SharingStarted.WhileSubscribed(5000), ...) over a
    // combine/flatMapLatest chain - it only starts collecting its upstream once something
    // subscribes, so every test here must subscribe via Turbine's .test{} (which also
    // matches this codebase's existing Flow-testing convention, e.g. ReviewViewModelTest.kt)
    // rather than reading .value cold. A StateFlow always replays its current value to a
    // new subscriber first (the seed emptyMap()), so expectMostRecentItem() after advancing
    // the dispatcher is used to skip past that seed to the settled value, rather than
    // asserting on a specific emission count.

    @Test
    fun `uploadProgress reflects live WorkInfo for an uploading report`() = runTest {
        val reportId = UUID.randomUUID().toString()
        every { reportRepository.observeReports() } returns MutableStateFlow(listOf(report(reportId, ReportStatus.UPLOADING)))
        val workInfoFlow = MutableStateFlow(listOf(workInfo(500_000L, 1_000_000L, 2_000_000L)))
        every { workManager.getWorkInfosForUniqueWorkFlow(UploadWorker.uniqueWorkName(reportId)) } returns workInfoFlow

        val viewModel = HistoryViewModel(reportRepository, authRepository, getReportStatusUseCase, retryUploadUseCase, workManager)

        viewModel.uploadProgress.test {
            testDispatcher.scheduler.advanceUntilIdle()
            val progress = expectMostRecentItem()[reportId]
            assertEquals(HistoryViewModel.UploadProgress(500_000L, 1_000_000L, 2_000_000L), progress)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `uploadProgress has no entry for a report with no WorkInfo yet`() = runTest {
        val reportId = UUID.randomUUID().toString()
        every { reportRepository.observeReports() } returns MutableStateFlow(listOf(report(reportId, ReportStatus.UPLOADING)))
        every { workManager.getWorkInfosForUniqueWorkFlow(UploadWorker.uniqueWorkName(reportId)) } returns MutableStateFlow(emptyList())

        val viewModel = HistoryViewModel(reportRepository, authRepository, getReportStatusUseCase, retryUploadUseCase, workManager)

        viewModel.uploadProgress.test {
            testDispatcher.scheduler.advanceUntilIdle()
            assertTrue(expectMostRecentItem().isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `uploadProgress has no entry for a report that is not UPLOADING`() = runTest {
        val reportId = UUID.randomUUID().toString()
        every { reportRepository.observeReports() } returns MutableStateFlow(listOf(report(reportId, ReportStatus.CONFIRMED)))

        val viewModel = HistoryViewModel(reportRepository, authRepository, getReportStatusUseCase, retryUploadUseCase, workManager)

        viewModel.uploadProgress.test {
            testDispatcher.scheduler.advanceUntilIdle()
            assertTrue(expectMostRecentItem().isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
