package com.trafficwatch.app.feature.camera

import android.annotation.SuppressLint
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trafficwatch.app.core.domain.model.LocationData
import com.trafficwatch.app.core.domain.model.RotationSample
import java.io.File

@SuppressLint("MissingPermission")
@Composable
fun CameraScreen(
    onVideoRecorded: (file: File, location: LocationData?, recordingStartedAt: Long, locationSamples: List<LocationData>, rotationSamples: List<RotationSample>) -> Unit,
    viewModel: CameraViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val recordingState by viewModel.recordingState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val previewView = remember { PreviewView(context) }
    val outputFile = remember { viewModel.newRawFile() }
    val isRecording = recordingState is RecordingState.Recording

    // Prevent the screen from dimming/sleeping mid-recording - a phone locking or the
    // display dimming would interrupt the clip. Scoped to the recording window only, not
    // the whole camera screen (e.g. while idle waiting for a GPS fix).
    val view = LocalView.current
    DisposableEffect(isRecording) {
        view.keepScreenOn = isRecording
        onDispose { view.keepScreenOn = false }
    }

    // Reset state when entering this screen
    LaunchedEffect(Unit) {
        viewModel.resetRecordingState()
    }

    // Bind CameraX to the current lifecycle + preview surface
    LaunchedEffect(previewView) {
        viewModel.bindCamera(previewView, lifecycleOwner)
    }

    // The orientation listener is a plain sensor listener, not tied to CameraX's
    // lifecycle-aware binding, so it needs to be stopped explicitly.
    DisposableEffect(Unit) {
        onDispose { viewModel.stopOrientationTracking() }
    }

    // Navigate forward once recording is finalised. Immediately resets the recording
    // state back to Idle after consuming it - CameraController's recordingState is
    // @Singleton-scoped (survives across separate CameraViewModel instances), so leaving
    // it at Finalizing would make the NEXT visit to this screen immediately re-fire this
    // same effect with the stale, previous recording before the user ever sees the camera
    // preview. Resetting here, at the point of consumption, closes that race at its
    // source - the separate on-entry reset below remains as a defensive backstop only.
    LaunchedEffect(recordingState) {
        if (recordingState is RecordingState.Finalizing) {
            onVideoRecorded(
                (recordingState as RecordingState.Finalizing).outputFile,
                viewModel.getSnapshotLocation(),
                viewModel.getRecordingStartedAt(),
                viewModel.getLocationSamples(),
                viewModel.getRotationSamples()
            )
            viewModel.resetRecordingState()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {

        // Camera preview
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        // GPS status badge (top-left)
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
        ) {
            GpsBadge(uiState.locationState)
        }

        // Recording timer (top-right)
        if (recordingState is RecordingState.Recording) {
            val elapsed = (recordingState as RecordingState.Recording).elapsedMs
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RecordingDot()
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = formatElapsed(elapsed),
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }

        // Record / Stop FAB (bottom-centre)
        val locationReady = uiState.locationState is LocationState.Fixed

        FloatingActionButton(
            onClick = {
                if (isRecording) viewModel.stopRecording()
                else if (locationReady) viewModel.onStartRecording(outputFile)
            },
            containerColor = if (isRecording) Color(0xFFD32F2F) else MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
                .size(72.dp)
        ) {
            Icon(
                imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                contentDescription = if (isRecording) "Stop" else "Record",
                tint = Color.White,
                modifier = Modifier.size(36.dp)
            )
        }

        // GPS not-ready hint
        if (!locationReady && recordingState is RecordingState.Idle) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 136.dp)
                    .padding(horizontal = 24.dp)
            ) {
                Snackbar { Text("Waiting for GPS fix before recording…") }
            }
        }

        // Camera error snackbar
        uiState.cameraError?.let { error ->
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 136.dp)
                    .padding(horizontal = 24.dp)
            ) {
                Snackbar(action = {
                    TextButton(onClick = viewModel::clearError) { Text("Dismiss") }
                }) {
                    Text(error)
                }
            }
        }
    }
}

@Composable
private fun GpsBadge(locationState: LocationState) {
    val (color, label) = when (locationState) {
        is LocationState.Fixed -> Color(0xFF43A047) to "GPS Fixed"
        is LocationState.Acquiring -> Color(0xFFFB8C00) to "Acquiring GPS…"
        is LocationState.Unavailable -> Color(0xFFE53935) to "GPS Unavailable"
    }
    Row(
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(6.dp))
        Icon(Icons.Default.LocationOn, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun RecordingDot() {
    val infiniteTransition = rememberInfiniteTransition(label = "rec_dot")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 0.2f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "rec_alpha"
    )
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(Color(0xFFD32F2F).copy(alpha = alpha))
    )
}

private fun formatElapsed(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
