package com.trafficwatch.app.core.domain.usecase

import android.content.Context
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.WorkManager
import com.trafficwatch.app.core.data.remote.dto.SampleJson
import com.trafficwatch.app.core.data.repository.ReportRepository
import com.trafficwatch.app.core.domain.model.LocationData
import com.trafficwatch.app.core.domain.model.Report
import com.trafficwatch.app.core.domain.model.ReportStatus
import com.trafficwatch.app.core.domain.model.RotationSample
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
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SubmitReportUseCaseTest {

    private val context = mockk<Context>(relaxed = true)
    private val reportRepository = mockk<ReportRepository>()
    private val networkMonitor = mockk<NetworkMonitor>()
    private val workManager = mockk<WorkManager>()
    private val enqueuedRequest = slot<OneTimeWorkRequest>()

    private lateinit var useCase: SubmitReportUseCase

    private val location = LocationData(31.5, 74.3, 5f, 200.0, 90f, 10f, 1000L)
    private val locationSamples = listOf(location, location.copy(capturedAt = 2000L))
    private val rotationSamples = listOf(RotationSample(capturedAt = 1000L, headingDegrees = 91.5f))

    private val report = Report(
        id = "r1",
        videoPath = "/v.mp4",
        location = location,
        recordingStartedAt = 1_000L,
        durationMs = 6_000L,
        fileSizeBytes = 0L,
        status = ReportStatus.UPLOADING,
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
        useCase = SubmitReportUseCase(context, reportRepository, networkMonitor)
    }

    @After
    fun tearDown() {
        unmockkStatic(WorkManager::class)
    }

    @Test
    fun `invoke persists serialized sample json on the report row`() = runTest {
        val saved = slot<Report>()
        coEvery { reportRepository.saveReport(capture(saved)) } just Runs
        every { networkMonitor.isOnWifi() } returns true

        useCase.invoke(File("/v.mp4"), location, locationSamples, rotationSamples, 1_000L, 6_000L)

        assertEquals(SampleJson.location(locationSamples), saved.captured.locationSamplesJson)
        assertEquals(SampleJson.rotation(rotationSamples), saved.captured.rotationSamplesJson)
    }

    @Test
    fun `invoke persists compass heading and zoom ratio from the recording snapshot`() = runTest {
        val saved = slot<Report>()
        coEvery { reportRepository.saveReport(capture(saved)) } just Runs
        every { networkMonitor.isOnWifi() } returns true

        val snapshot = location.copy(compassHeadingDegrees = 123.4f, zoomRatio = 1.5f)
        useCase.invoke(File("/v.mp4"), snapshot, locationSamples, rotationSamples, 1_000L, 6_000L)

        assertEquals(123.4f, saved.captured.location.compassHeadingDegrees)
        assertEquals(1.5f, saved.captured.location.zoomRatio)
    }

    @Test
    fun `confirmCellular re-enqueues from the persisted row`() = runTest {
        coEvery { reportRepository.getReport("r1") } returns
            report.copy(locationSamplesJson = """[{"x":1}]""", rotationSamplesJson = null)

        useCase.confirmCellular("r1")

        val input = enqueuedRequest.captured.workSpec.input
        assertEquals("""[{"x":1}]""", input.getString(UploadWorker.KEY_LOCATION_SAMPLES_JSON))
        assertNull(input.getString(UploadWorker.KEY_ROTATION_SAMPLES_JSON))
    }

    @Test
    fun `confirmCellular on a row miss does not enqueue and does not crash`() = runTest {
        coEvery { reportRepository.getReport("r1") } returns null

        useCase.confirmCellular("r1")

        assertEquals(false, enqueuedRequest.isCaptured)
    }
}
