package com.trafficwatch.app.feature.review

import app.cash.turbine.test
import com.trafficwatch.app.core.domain.model.LocationData
import com.trafficwatch.app.core.domain.usecase.SubmitReportResult
import com.trafficwatch.app.core.domain.usecase.SubmitReportUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val submitReportUseCase = mockk<SubmitReportUseCase>()
    private lateinit var viewModel: ReviewViewModel

    private val location = LocationData(31.5, 74.3, 5f, 200.0, 90f, 10f, 1000L)
    private val locationSamples = listOf(location, location.copy(capturedAt = 2000L))
    private val testFile = File("/tmp/clip.mp4")

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        viewModel = ReviewViewModel(submitReportUseCase)
        viewModel.init(testFile, location, locationSamples, emptyList(), 1000L, 8000L)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `submit on wifi fires submitted without showing cellular prompt`() = runTest {
        coEvery { submitReportUseCase(any(), any(), any(), any(), any(), any()) } returns
            SubmitReportResult(reportId = "r1", effectiveLocation = location, onWifi = true)

        viewModel.submit()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.submitted.test { awaitItem() }
        assertFalse(viewModel.uiState.value.showCellularPrompt)
        coVerify(exactly = 1) {
            submitReportUseCase(File(testFile.absolutePath), location, locationSamples, emptyList(), 1000L, 8000L)
        }
    }

    @Test
    fun `submit off wifi shows cellular prompt without firing submitted yet`() = runTest {
        coEvery { submitReportUseCase(any(), any(), any(), any(), any(), any()) } returns
            SubmitReportResult(reportId = "r1", effectiveLocation = location, onWifi = false)

        viewModel.submit()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.showCellularPrompt)
    }

    @Test
    fun `confirmCellularSubmit re-enqueues over cellular, clears prompt, and fires submitted`() = runTest {
        coEvery { submitReportUseCase(any(), any(), any(), any(), any(), any()) } returns
            SubmitReportResult(reportId = "r1", effectiveLocation = location, onWifi = false)
        coEvery {
            submitReportUseCase.confirmCellular(any(), any(), any(), any(), any(), any(), any())
        } just runs

        viewModel.submit()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.confirmCellularSubmit()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.submitted.test { awaitItem() }
        coVerify(exactly = 1) {
            submitReportUseCase.confirmCellular("r1", testFile.absolutePath, location, locationSamples, emptyList(), 1000L, 8000L)
        }
        assertFalse(viewModel.uiState.value.showCellularPrompt)
    }

    @Test
    fun `double-tapping submit before it resolves only invokes the use case once`() = runTest {
        coEvery { submitReportUseCase(any(), any(), any(), any(), any(), any()) } returns
            SubmitReportResult(reportId = "r1", effectiveLocation = location, onWifi = true)

        viewModel.submit()
        viewModel.submit()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { submitReportUseCase(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `dismissCellularPrompt clears prompt and fires submitted without re-enqueuing`() = runTest {
        coEvery { submitReportUseCase(any(), any(), any(), any(), any(), any()) } returns
            SubmitReportResult(reportId = "r1", effectiveLocation = location, onWifi = false)

        viewModel.submit()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.dismissCellularPrompt()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.submitted.test { awaitItem() }
        coVerify(exactly = 0) {
            submitReportUseCase.confirmCellular(any(), any(), any(), any(), any(), any(), any())
        }
        assertFalse(viewModel.uiState.value.showCellularPrompt)
    }

    @Test
    fun `updateLocation replaces lat-lon and accuracy, and marks location confirmed`() {
        viewModel.updateLocation(latitude = 31.6, longitude = 74.4)

        val updated = viewModel.uiState.value.location
        assertEquals(31.6, updated?.latitude)
        assertEquals(74.4, updated?.longitude)
        assertEquals(ReviewViewModel.CONFIRMED_ACCURACY_METERS, updated?.accuracy)
        assertTrue(viewModel.uiState.value.locationConfirmed)
    }

    @Test
    fun `re-init after confirmation keeps the confirmed location, not the original`() {
        viewModel.updateLocation(latitude = 31.6, longitude = 74.4)
        // Simulates ReviewScreen re-entering composition (and its LaunchedEffect re-firing
        // init()) after ConfirmLocationScreen pops back - the exact sequence that caused the
        // Critical bug this test guards against.
        viewModel.init(testFile, location, locationSamples, emptyList(), 1000L, 8000L)

        val updated = viewModel.uiState.value.location
        assertEquals(31.6, updated?.latitude)
        assertEquals(74.4, updated?.longitude)
        assertEquals(ReviewViewModel.CONFIRMED_ACCURACY_METERS, updated?.accuracy)
        assertTrue(viewModel.uiState.value.locationConfirmed)
    }

    @Test
    fun `locationConfirmed defaults to false`() {
        assertFalse(viewModel.uiState.value.locationConfirmed)
    }

    @Test
    fun `updateLocation on a null location is a no-op`() {
        viewModel.init(testFile, null, locationSamples, emptyList(), 1000L, 8000L)

        viewModel.updateLocation(latitude = 31.6, longitude = 74.4)

        assertEquals(null, viewModel.uiState.value.location)
        assertFalse(viewModel.uiState.value.locationConfirmed)
    }
}
