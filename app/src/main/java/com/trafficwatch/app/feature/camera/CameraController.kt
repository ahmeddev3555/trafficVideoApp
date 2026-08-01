package com.trafficwatch.app.feature.camera

import android.content.Context
import android.view.OrientationEventListener
import android.view.Surface
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
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

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
                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, videoCapture)
                startOrientationTracking()
                onBound()
            } catch (e: Exception) {
                onError(e.message ?: "Camera bind failed")
            }
        }, ContextCompat.getMainExecutor(context))
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
