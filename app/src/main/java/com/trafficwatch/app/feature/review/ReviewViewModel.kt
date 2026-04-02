package com.trafficwatch.app.feature.review

import androidx.lifecycle.ViewModel
import com.trafficwatch.app.core.domain.model.LocationData
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import javax.inject.Inject

data class ReviewUiState(
    val trimmedFilePath: String = "",
    val location: LocationData? = null,
    val recordingStartedAt: Long = 0L,
    val durationMs: Long = 0L,
    val fileSizeBytes: Long = 0L
)

@HiltViewModel
class ReviewViewModel @Inject constructor() : ViewModel() {
    private val _uiState = MutableStateFlow(ReviewUiState())
    val uiState = _uiState.asStateFlow()

    fun init(
        trimmedFile: File,
        location: LocationData?,
        recordingStartedAt: Long,
        durationMs: Long
    ) {
        _uiState.update {
            it.copy(
                trimmedFilePath = trimmedFile.absolutePath,
                location = location,
                recordingStartedAt = recordingStartedAt,
                durationMs = durationMs,
                fileSizeBytes = trimmedFile.length()
            )
        }
    }
}
