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

data class TrimUiState(
    val videoPath: String = "",
    val totalDurationMs: Long = 0L,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = 0L,
    val trimProgress: TrimProgress = TrimProgress.Idle
)

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
                trimEndMs = minOf(durationMs, 60_000L),
                trimProgress = TrimProgress.Idle
            )
        }
    }

    fun onRangeChange(startMs: Long, endMs: Long) {
        _uiState.update { it.copy(trimStartMs = startMs, trimEndMs = endMs) }
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
