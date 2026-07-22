package com.trafficwatch.app.feature.trim

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trafficwatch.app.core.domain.usecase.TrimVideoUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

private const val MAX_TRIM_DURATION_MS = 5_000L

data class TrimUiState(
    val videoPath: String = "",
    val totalDurationMs: Long = 0L,
    val trimStartMs: Long = 0L,
    val maxDurationMs: Long = MAX_TRIM_DURATION_MS,
    val scrubPositionMs: Long? = null,
    val trimProgress: TrimProgress = TrimProgress.Idle
) {
    /** The selection is always exactly [maxDurationMs] long, or the whole clip if it's shorter. */
    val selectedDurationMs: Long get() = minOf(maxDurationMs, totalDurationMs)
    val trimEndMs: Long get() = trimStartMs + selectedDurationMs
}

@HiltViewModel
class TrimViewModel @Inject constructor(
    private val trimVideoUseCase: TrimVideoUseCase,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _uiState = MutableStateFlow(TrimUiState())
    val uiState = _uiState.asStateFlow()

    private var trimJob: Job? = null

    fun initVideo(path: String, durationMs: Long) {
        _uiState.update {
            it.copy(
                videoPath = path,
                totalDurationMs = durationMs,
                trimStartMs = 0L,
                trimProgress = TrimProgress.Idle
            )
        }
    }

    /** Moves the fixed-length selection window so it starts at [startMs], clamped to the video's bounds. */
    fun onWindowPositionChange(startMs: Long) {
        _uiState.update { it.copy(trimStartMs = clampWindowStart(startMs, it)) }
    }

    /** Shifts the window by [deltaMs] (positive = later, negative = earlier), clamped to the video's bounds. */
    fun nudgeWindow(deltaMs: Long) {
        _uiState.update { it.copy(trimStartMs = clampWindowStart(it.trimStartMs + deltaMs, it)) }
    }

    private fun clampWindowStart(startMs: Long, state: TrimUiState): Long {
        val maxStart = (state.totalDurationMs - state.selectedDurationMs).coerceAtLeast(0L)
        return startMs.coerceIn(0L, maxStart)
    }

    fun onScrubChange(positionMs: Long?) {
        _uiState.update { it.copy(scrubPositionMs = positionMs) }
    }

    fun confirmTrim() {
        val state = _uiState.value
        trimJob?.cancel()
        trimJob = viewModelScope.launch {
            trimVideoUseCase(
                File(state.videoPath),
                state.trimStartMs,
                state.trimEndMs
            ).collect { progress ->
                _uiState.update { it.copy(trimProgress = progress) }
            }
        }
    }

    fun resetTrimProgress() {
        _uiState.update { it.copy(trimProgress = TrimProgress.Idle) }
    }
}
