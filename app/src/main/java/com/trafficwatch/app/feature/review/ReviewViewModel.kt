package com.trafficwatch.app.feature.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trafficwatch.app.core.domain.model.LocationData
import com.trafficwatch.app.core.domain.usecase.SubmitReportUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class ReviewUiState(
    val trimmedFilePath: String = "",
    val location: LocationData? = null,
    val locationSamples: List<LocationData> = emptyList(),
    val recordingStartedAt: Long = 0L,
    val durationMs: Long = 0L,
    val fileSizeBytes: Long = 0L,
    val showCellularPrompt: Boolean = false,
    val isSubmitting: Boolean = false
)

@HiltViewModel
class ReviewViewModel @Inject constructor(
    private val submitReportUseCase: SubmitReportUseCase
) : ViewModel() {
    private val _uiState = MutableStateFlow(ReviewUiState())
    val uiState = _uiState.asStateFlow()

    // Buffered (not conflated) so a send that happens before the collector attaches - e.g.
    // ReviewScreen's LaunchedEffect racing the coroutine launched by submit() - is never
    // dropped; ReviewScreen only ever calls submit() once per screen instance, so at most
    // one item is ever buffered in practice.
    private val _submitted = Channel<Unit>(Channel.BUFFERED)
    val submitted: Flow<Unit> = _submitted.receiveAsFlow()

    private var lastReportId: String? = null
    private var lastEffectiveLocation: LocationData? = null

    fun init(
        trimmedFile: File,
        location: LocationData?,
        locationSamples: List<LocationData>,
        recordingStartedAt: Long,
        durationMs: Long
    ) {
        _uiState.update {
            it.copy(
                trimmedFilePath = trimmedFile.absolutePath,
                location = location,
                locationSamples = locationSamples,
                recordingStartedAt = recordingStartedAt,
                durationMs = durationMs,
                fileSizeBytes = trimmedFile.length()
            )
        }
    }

    fun submit() {
        if (_uiState.value.isSubmitting) return
        val state = _uiState.value
        _uiState.update { it.copy(isSubmitting = true) }
        viewModelScope.launch {
            val result = submitReportUseCase(
                File(state.trimmedFilePath), state.location, state.locationSamples, state.recordingStartedAt, state.durationMs
            )
            lastReportId = result.reportId
            lastEffectiveLocation = result.effectiveLocation
            if (result.onWifi) {
                _uiState.update { it.copy(isSubmitting = false) }
                _submitted.send(Unit)
            } else {
                _uiState.update { it.copy(showCellularPrompt = true, isSubmitting = false) }
            }
        }
    }

    /** User explicitly confirmed uploading over cellular data for the current submission. */
    fun confirmCellularSubmit() {
        if (_uiState.value.isSubmitting) return
        val reportId = lastReportId ?: return
        val location = lastEffectiveLocation ?: return
        val state = _uiState.value
        _uiState.update { it.copy(isSubmitting = true) }
        viewModelScope.launch {
            submitReportUseCase.confirmCellular(
                reportId, state.trimmedFilePath, location, state.locationSamples, state.recordingStartedAt, state.durationMs
            )
            _uiState.update { it.copy(showCellularPrompt = false, isSubmitting = false) }
            _submitted.send(Unit)
        }
    }

    /** User dismissed the cellular prompt - the Wi-Fi-only enqueue from submit() already stands. */
    fun dismissCellularPrompt() {
        _uiState.update { it.copy(showCellularPrompt = false) }
        viewModelScope.launch { _submitted.send(Unit) }
    }
}
