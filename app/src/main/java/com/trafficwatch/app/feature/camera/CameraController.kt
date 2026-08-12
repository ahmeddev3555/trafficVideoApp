package com.trafficwatch.app.feature.camera

import android.content.Context
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max
import kotlin.math.min
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** This app's own hard cap, independent of whatever a device's hardware would otherwise allow. */
private const val APP_MAX_ZOOM_RATIO = 2.0f
private const val APP_MIN_ZOOM_RATIO = 1.0f

/**
 * Clamps [requested] to the intersection of this app's own [APP_MIN_ZOOM_RATIO]/
 * [APP_MAX_ZOOM_RATIO] cap and the device's actual supported range
 * ([deviceMinZoomRatio]/[deviceMaxZoomRatio], from [androidx.camera.core.ZoomState]) - so
 * [androidx.camera.core.CameraControl.setZoomRatio] is never called with a value outside
 * what the device itself would accept, on a device whose own range doesn't happen to
 * bracket this app's [1.0, 2.0] range exactly.
 */
internal fun clampZoomRatio(requested: Float, deviceMinZoomRatio: Float, deviceMaxZoomRatio: Float): Float {
    val lowerBound = max(APP_MIN_ZOOM_RATIO, deviceMinZoomRatio)
    val upperBound = min(APP_MAX_ZOOM_RATIO, deviceMaxZoomRatio)
    return requested.coerceIn(lowerBound, upperBound)
}

sealed class RecordingState {
    object Idle : RecordingState()
    data class Recording(val elapsedMs: Long = 0L) : RecordingState()
    data class Finalizing(val outputFile: File) : RecordingState()
}

@Singleton
class CameraController @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val _recordingState = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val recordingState = _recordingState.asStateFlow()

    private val _currentRotation = MutableStateFlow(Surface.ROTATION_0)
    val currentRotation = _currentRotation.asStateFlow()

    private val _currentZoomRatio = MutableStateFlow(APP_MIN_ZOOM_RATIO)
    val currentZoomRatio = _currentZoomRatio.asStateFlow()

    private var camera: Camera? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null

    // The Activity is portrait-locked, so display rotation never changes and CameraX's
    // default (display-rotation-based) target rotation would always record as portrait
    // regardless of how the phone is physically held. Tracking physical device
    // orientation directly and feeding it to VideoCapture is what makes a sideways-held
    // recording actually come out landscape.
    private var orientationEventListener: OrientationEventListener? = null

    private fun ensureOrientationListener(): OrientationEventListener =
        orientationEventListener ?: object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val rotation = when (orientation) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                videoCapture?.targetRotation = rotation
                _currentRotation.value = rotation
            }
        }.also { orientationEventListener = it }

    private fun startOrientationTracking() {
        ensureOrientationListener().enable()
    }

    fun stopOrientationTracking() {
        orientationEventListener?.disable()
    }

    fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onBound: () -> Unit,
        onError: (String) -> Unit
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            // Quality.HIGHEST previously recorded at the device's native max (4K on some
            // phones), which made vehicles proportionally tiny once YOLO downscales the
            // frame for inference - motorcycles in particular went completely undetected.
            // FHD (1080p) is far more resolution than vehicle detection or plate OCR need
            // at realistic bystander-filming distances.
            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD)),
                )
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, videoCapture)
                _currentZoomRatio.value = APP_MIN_ZOOM_RATIO
                startOrientationTracking()
                onBound()
            } catch (e: Exception) {
                onError(e.message ?: "Camera bind failed")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Requests [requested] as the new zoom ratio, clamped via [clampZoomRatio] against both
     * this app's own cap and the bound camera's actual supported range. A no-op before
     * [bindCamera] has completed (no bound [Camera] yet) - the zoom controls are only shown
     * once the preview is live, so this should not normally be reachable that early, but a
     * stray call must not crash rather than silently do nothing.
     */
    fun setZoomRatio(requested: Float) {
        val boundCamera = camera ?: return
        val zoomState = boundCamera.cameraInfo.zoomState.value ?: return
        val clamped = clampZoomRatio(requested, zoomState.minZoomRatio, zoomState.maxZoomRatio)
        boundCamera.cameraControl.setZoomRatio(clamped)
        _currentZoomRatio.value = clamped
    }

    @androidx.annotation.OptIn(androidx.camera.video.ExperimentalPersistentRecording::class)
    fun startRecording(outputFile: File, onError: (String) -> Unit) {
        val vc = videoCapture ?: run { onError("Camera not ready"); return }

        val fileOutputOptions = FileOutputOptions.Builder(outputFile).build()

        activeRecording = vc.output
            .prepareRecording(context, fileOutputOptions)
            .start(ContextCompat.getMainExecutor(context)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        _recordingState.value = RecordingState.Recording()
                    }
                    is VideoRecordEvent.Status -> {
                        val elapsed = event.recordingStats.recordedDurationNanos / 1_000_000
                        _recordingState.value = RecordingState.Recording(elapsed)
                    }
                    is VideoRecordEvent.Finalize -> {
                        if (event.hasError()) {
                            _recordingState.value = RecordingState.Idle
                            onError("Recording error: ${event.error}")
                        } else {
                            _recordingState.value = RecordingState.Finalizing(outputFile)
                        }
                    }
                    else -> Unit
                }
            }
    }

    fun stopRecording() {
        activeRecording?.stop()
        activeRecording = null
    }

    fun resetState() {
        _recordingState.value = RecordingState.Idle
    }
}
