package com.trafficwatch.app.feature.camera

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
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

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, videoCapture)
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
            .withAudioEnabled()
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
