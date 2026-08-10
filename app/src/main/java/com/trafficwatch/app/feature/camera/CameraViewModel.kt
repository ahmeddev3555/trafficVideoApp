package com.trafficwatch.app.feature.camera

import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trafficwatch.app.core.domain.model.LocationData
import com.trafficwatch.app.core.domain.model.RotationSample
import com.trafficwatch.app.core.util.CompassProvider
import com.trafficwatch.app.core.util.FileUtil
import com.trafficwatch.app.core.util.LocationUtil
import com.trafficwatch.app.core.util.MAX_ACCEPTABLE_ACCURACY_METERS
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

private const val MAX_RECORDING_MS = 600_000L
private const val RECORDING_SAMPLE_INTERVAL_MS = 1_000L
private const val ROTATION_SAMPLE_INTERVAL_MS = 200L

sealed class LocationState {
    object Acquiring : LocationState()
    data class Fixed(val data: LocationData) : LocationState()
    object Unavailable : LocationState()
}

data class CameraUiState(
    val locationState: LocationState = LocationState.Acquiring,
    val cameraError: String? = null,
    val currentHeadingDegrees: Float? = null
)

@HiltViewModel
class CameraViewModel @Inject constructor(
    private val cameraController: CameraController,
    private val locationUtil: LocationUtil,
    private val compassProvider: CompassProvider,
    private val fileUtil: FileUtil
) : ViewModel() {

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState = _uiState.asStateFlow()
    val recordingState = cameraController.recordingState

    private var maxDurationJob: Job? = null
    private var snapshotLocation: LocationData? = null
    private var snapshotCompassHeading: Float? = null
    private var recordingStartedAt: Long = 0L
    private val locationSamples = mutableListOf<LocationData>()
    private var samplingJob: Job? = null
    private val rotationSamples = mutableListOf<RotationSample>()
    private var rotationSamplingJob: Job? = null

    init {
        observeLocation()
    }

    private fun observeLocation() {
        viewModelScope.launch {
            locationUtil.observeLocation().collect { loc ->
                _uiState.update {
                    it.copy(
                        locationState = when {
                            loc == null -> LocationState.Unavailable
                            loc.accuracy > MAX_ACCEPTABLE_ACCURACY_METERS -> LocationState.Acquiring
                            else -> LocationState.Fixed(loc)
                        }
                    )
                }
            }
        }
    }

    fun bindCamera(previewView: PreviewView, lifecycleOwner: LifecycleOwner) {
        cameraController.bindCamera(
            lifecycleOwner = lifecycleOwner,
            previewView = previewView,
            onBound = {},
            onError = { error -> _uiState.update { it.copy(cameraError = error) } }
        )
    }

    fun newRawFile(): File = fileUtil.newRawRecordingFile()

    fun onStartRecording(outputFile: File) {
        // Defensive cancellation: a prior recording's error path (see the onError callback
        // below) can leave these jobs running without ever going through stopRecording(), so
        // a fresh start must not let a leaked prior sampling job double up with this one.
        samplingJob?.cancel()
        rotationSamplingJob?.cancel()
        maxDurationJob?.cancel()

        recordingStartedAt = System.currentTimeMillis()

        // The record button only enables once locationState is Fixed, so this is a real
        // fix, not a stale/placeholder one - used for magnetic declination without waiting
        // on a fresh GPS read (which would otherwise serialize behind the compass read).
        val declinationReference = (uiState.value.locationState as? LocationState.Fixed)?.data

        locationSamples.clear()
        samplingJob = viewModelScope.launch {
            locationUtil.observeLocation(RECORDING_SAMPLE_INTERVAL_MS)
                .filterNotNull()
                .collect { locationSamples.add(it) }
        }

        rotationSamples.clear()
        rotationSamplingJob = viewModelScope.launch {
            val headings = if (declinationReference != null) {
                compassProvider.observeHeadings(
                    latitude = declinationReference.latitude,
                    longitude = declinationReference.longitude,
                    altitude = declinationReference.altitude,
                    intervalMs = ROTATION_SAMPLE_INTERVAL_MS,
                    currentRotation = cameraController.currentRotation,
                )
            } else {
                // No location fix available for declination correction - falls back to
                // magnetic-north-only headings, same as the one-shot snapshot's fallback
                // right below.
                compassProvider.observeHeadings(
                    latitude = 0.0, longitude = 0.0, altitude = 0.0,
                    intervalMs = ROTATION_SAMPLE_INTERVAL_MS,
                    currentRotation = cameraController.currentRotation,
                )
            }
            headings.filterNotNull().collect { heading ->
                rotationSamples.add(RotationSample(capturedAt = System.currentTimeMillis(), headingDegrees = heading))
                _uiState.update { it.copy(currentHeadingDegrees = heading) }
            }
        }

        viewModelScope.launch {
            snapshotLocation = locationUtil.getSnapshot()
        }
        viewModelScope.launch {
            snapshotCompassHeading = if (declinationReference != null) {
                compassProvider.getSnapshot(
                    latitude = declinationReference.latitude,
                    longitude = declinationReference.longitude,
                    altitude = declinationReference.altitude,
                    rotation = cameraController.currentRotation.value,
                )
            } else {
                // No location fix available for declination correction - falls back to a
                // magnetic-north-only heading rather than skipping compass capture entirely.
                compassProvider.getSnapshot(latitude = 0.0, longitude = 0.0, altitude = 0.0, rotation = cameraController.currentRotation.value)
            }
        }

        cameraController.startRecording(outputFile) { error ->
            // CameraController has already moved to Idle without going through
            // stopRecording(), so these jobs must be cancelled here or the 1Hz GPS
            // subscription (and the max-duration timer) leak indefinitely.
            samplingJob?.cancel()
            rotationSamplingJob?.cancel()
            maxDurationJob?.cancel()
            _uiState.update { it.copy(cameraError = error, currentHeadingDegrees = null) }
        }
        maxDurationJob = viewModelScope.launch {
            delay(MAX_RECORDING_MS)
            stopRecording()
        }
    }

    fun stopRecording() {
        maxDurationJob?.cancel()
        samplingJob?.cancel()
        rotationSamplingJob?.cancel()
        cameraController.stopRecording()
        _uiState.update { it.copy(currentHeadingDegrees = null) }
    }

    fun getSnapshotLocation(): LocationData? =
        snapshotLocation?.copy(compassHeadingDegrees = snapshotCompassHeading)

    fun getLocationSamples(): List<LocationData> = locationSamples.toList()

    fun getRotationSamples(): List<RotationSample> = rotationSamples.toList()

    fun getRecordingStartedAt(): Long = recordingStartedAt

    fun resetRecordingState() = cameraController.resetState()

    fun stopOrientationTracking() = cameraController.stopOrientationTracking()

    fun clearError() = _uiState.update { it.copy(cameraError = null) }
}
