package com.trafficwatch.app.feature.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trafficwatch.app.core.domain.model.LocationData
import com.trafficwatch.app.core.domain.model.RotationSample
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
    val rotationSamples: List<RotationSample> = emptyList(),
    val recordingStartedAt: Long = 0L,
    val durationMs: Long = 0L,
    val fileSizeBytes: Long = 0L,
    val showCellularPrompt: Boolean = false,
    val isSubmitting: Boolean = false,
    val locationConfirmed: Boolean = false
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

    fun init(
        trimmedFile: File,
        location: LocationData?,
        locationSamples: List<LocationData>,
        rotationSamples: List<RotationSample>,
        recordingStartedAt: Long,
        durationMs: Long
    ) {
        _uiState.update {
            it.copy(
                trimmedFilePath = trimmedFile.absolutePath,
                // ReviewScreen re-enters composition (and re-runs this LaunchedEffect) when
                // ConfirmLocationScreen pops back, since Navigation Compose fully disposes a
                // destination's composable while it's not the current back-stack top - init()
                // must not clobber a location the user already confirmed with the original,
                // now-stale caller-supplied value.
                location = if (it.locationConfirmed) it.location else location,
                locationSamples = locationSamples,
                rotationSamples = rotationSamples,
                recordingStartedAt = recordingStartedAt,
                durationMs = durationMs,
                fileSizeBytes = trimmedFile.length()
            )
        }
    }

    /**
     * Replaces the current location's latitude/longitude with a user-confirmed/corrected
     * position from [com.trafficwatch.app.feature.confirmlocation.ConfirmLocationScreen], and
     * replaces its accuracy with [CONFIRMED_ACCURACY_METERS] - once a human has looked at a
     * map and explicitly agreed with (or corrected) the position, that's treated as
     * higher-confidence than the raw GPS reading it started from, regardless of whether the
     * pin was actually moved.
     */
    fun updateLocation(latitude: Double, longitude: Double) {
        _uiState.update { state ->
            val current = state.location ?: return@update state
            state.copy(
                location = current.copy(latitude = latitude, longitude = longitude, accuracy = CONFIRMED_ACCURACY_METERS),
                locationConfirmed = true
            )
        }
    }

    fun submit() {
        if (_uiState.value.isSubmitting) return
        val state = _uiState.value
        _uiState.update { it.copy(isSubmitting = true) }
        viewModelScope.launch {
            val result = submitReportUseCase(
                File(state.trimmedFilePath), state.location, state.locationSamples, state.rotationSamples, state.recordingStartedAt, state.durationMs
            )
            lastReportId = result.reportId
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
        _uiState.update { it.copy(isSubmitting = true) }
        viewModelScope.launch {
            submitReportUseCase.confirmCellular(reportId)
            _uiState.update { it.copy(showCellularPrompt = false, isSubmitting = false) }
            _submitted.send(Unit)
        }
    }

    /** User dismissed the cellular prompt - the Wi-Fi-only enqueue from submit() already stands. */
    fun dismissCellularPrompt() {
        _uiState.update { it.copy(showCellularPrompt = false) }
        viewModelScope.launch { _submitted.send(Unit) }
    }

    companion object {
        const val ACCURACY_THRESHOLD_METERS = 10f
        const val CONFIRMED_ACCURACY_METERS = 5f
    }
}
