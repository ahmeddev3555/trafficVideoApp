package com.trafficwatch.app.feature.trim

import androidx.lifecycle.SavedStateHandle
import com.trafficwatch.app.core.domain.usecase.TrimVideoUseCase
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class TrimViewModelTest {

    private lateinit var viewModel: TrimViewModel

    @Before
    fun setUp() {
        viewModel = TrimViewModel(mockk<TrimVideoUseCase>(), SavedStateHandle())
    }

    @Test
    fun `initVideo caps default selection at 5 seconds for a longer video`() {
        viewModel.initVideo("/tmp/video.mp4", durationMs = 30_000L)

        val state = viewModel.uiState.value
        assertEquals(0L, state.trimStartMs)
        assertEquals(5_000L, state.trimEndMs)
        assertEquals(5_000L, state.maxDurationMs)
    }

    @Test
    fun `initVideo selects the whole video when it is shorter than 5 seconds`() {
        viewModel.initVideo("/tmp/video.mp4", durationMs = 3_000L)

        val state = viewModel.uiState.value
        assertEquals(0L, state.trimStartMs)
        assertEquals(3_000L, state.trimEndMs)
    }

    @Test
    fun `initVideo selects exactly 5 seconds when the video is exactly 5 seconds`() {
        viewModel.initVideo("/tmp/video.mp4", durationMs = 5_000L)

        assertEquals(5_000L, viewModel.uiState.value.trimEndMs)
    }

    @Test
    fun `onWindowPositionChange moves the window and keeps its length fixed`() {
        viewModel.initVideo("/tmp/video.mp4", durationMs = 30_000L)

        viewModel.onWindowPositionChange(10_000L)

        val state = viewModel.uiState.value
        assertEquals(10_000L, state.trimStartMs)
        assertEquals(15_000L, state.trimEndMs)
    }

    @Test
    fun `onWindowPositionChange clamps to the video's bounds`() {
        viewModel.initVideo("/tmp/video.mp4", durationMs = 30_000L)

        viewModel.onWindowPositionChange(-500L)
        assertEquals(0L, viewModel.uiState.value.trimStartMs)

        viewModel.onWindowPositionChange(999_000L)
        assertEquals(25_000L, viewModel.uiState.value.trimStartMs) // 30_000 - 5_000
    }

    @Test
    fun `nudgeWindow shifts the window by the given delta`() {
        viewModel.initVideo("/tmp/video.mp4", durationMs = 30_000L)
        viewModel.onWindowPositionChange(10_000L)

        viewModel.nudgeWindow(5_000L)
        assertEquals(15_000L, viewModel.uiState.value.trimStartMs)

        viewModel.nudgeWindow(-2_000L)
        assertEquals(13_000L, viewModel.uiState.value.trimStartMs)
    }

    @Test
    fun `nudgeWindow clamps at the video's edges`() {
        viewModel.initVideo("/tmp/video.mp4", durationMs = 30_000L)
        viewModel.onWindowPositionChange(1_000L)

        viewModel.nudgeWindow(-30_000L)
        assertEquals(0L, viewModel.uiState.value.trimStartMs)

        viewModel.onWindowPositionChange(20_000L)
        viewModel.nudgeWindow(30_000L)
        assertEquals(25_000L, viewModel.uiState.value.trimStartMs) // 30_000 - 5_000
    }

    @Test
    fun `onScrubChange sets and clears the scrub position`() {
        viewModel.onScrubChange(2_500L)
        assertEquals(2_500L, viewModel.uiState.value.scrubPositionMs)

        viewModel.onScrubChange(null)
        assertNull(viewModel.uiState.value.scrubPositionMs)
    }
}
