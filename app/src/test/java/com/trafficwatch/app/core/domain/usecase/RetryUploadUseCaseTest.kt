package com.trafficwatch.app.core.domain.usecase

import android.content.Context
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.WorkManager
import com.trafficwatch.app.core.data.repository.ReportRepository
import com.trafficwatch.app.core.domain.model.LocationData
import com.trafficwatch.app.core.domain.model.Report
import com.trafficwatch.app.core.domain.model.ReportStatus
import com.trafficwatch.app.core.util.FileUtil
import com.trafficwatch.app.core.util.NetworkMonitor
import com.trafficwatch.app.feature.upload.UploadWorker
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RetryUploadUseCaseTest {

    private val context = mockk<Context>(relaxed = true)
    private val reportRepository = mockk<ReportRepository>()
    private val fileUtil = mockk<FileUtil>()
    private val networkMonitor = mockk<NetworkMonitor>()
    private val workManager = mockk<WorkManager>()
    private val enqueuedRequest = slot<OneTimeWorkRequest>()

    private lateinit var useCase: RetryUploadUseCase

    private val location = LocationData(31.5, 74.3, 5f, 200.0, 90f, 10f, 1000L)

    private val baseReport = Report(
        id = "r1",
        videoPath = "/v.mp4",
        location = location,
        recordingStartedAt = 1_000L,
        durationMs = 6_000L,
        fileSizeBytes = 0L,
        status = ReportStatus.UPLOAD_FAILED,
        createdAt = 1_000L,
        updatedAt = 1_000L,
    )

    @Before
    fun setUp() {
        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(context) } returns workManager
        every {
            workManager.enqueueUniqueWork(any(), any(), capture(enqueuedRequest))
        } returns mockk<Operation>(relaxed = true)
        every { fileUtil.exists(any()) } returns true
        coEvery { reportRepository.updateStatus(any(), any(), any()) } just Runs
        every { networkMonitor.isOnWifi() } returns true
        useCase = RetryUploadUseCase(context, reportRepository, fileUtil, networkMonitor)
    }

    @After
    fun tearDown() {
        unmockkStatic(WorkManager::class)
    }

    @Test
    fun `retry resends the persisted sample json`() = runTest {
        val report = baseReport.copy(
            locationSamplesJson = """[{"latitude":31.5}]""",
            rotationSamplesJson = """[{"heading_degrees":90.0}]""",
        )

        val result = useCase.invoke(report)

        val input = enqueuedRequest.captured.workSpec.input
        assertEquals(
            """[{"latitude":31.5}]""",
            input.getString(UploadWorker.KEY_LOCATION_SAMPLES_JSON),
        )
        assertEquals(
            """[{"heading_degrees":90.0}]""",
            input.getString(UploadWorker.KEY_ROTATION_SAMPLES_JSON),
        )
        assertEquals(RetryUploadResult.Enqueued(onWifi = true), result)
    }

    @Test
    fun `retry with no persisted samples omits the keys and still succeeds`() = runTest {
        val result = useCase.invoke(baseReport)

        val input = enqueuedRequest.captured.workSpec.input
        assertNull(input.getString(UploadWorker.KEY_LOCATION_SAMPLES_JSON))
        assertNull(input.getString(UploadWorker.KEY_ROTATION_SAMPLES_JSON))
        assertEquals(RetryUploadResult.Enqueued(onWifi = true), result)
    }
}
