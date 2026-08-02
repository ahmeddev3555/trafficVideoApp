package com.trafficwatch.app.feature.review

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.trafficwatch.app.core.domain.model.LocationData
import com.trafficwatch.app.core.ui.components.CellularConfirmDialog
import com.trafficwatch.app.core.util.FileUtil
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, androidx.media3.common.util.UnstableApi::class)
@Composable
fun ReviewScreen(
    trimmedFile: File,
    location: LocationData?,
    recordingStartedAt: Long,
    durationMs: Long,
    onSubmit: () -> Unit,
    onRetrim: () -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: ReviewViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(trimmedFile)))
            prepare()
        }
    }

    LaunchedEffect(trimmedFile.absolutePath) {
        viewModel.init(trimmedFile, location, recordingStartedAt, durationMs)
    }

    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }

    val currentOnSubmit by rememberUpdatedState(onSubmit)
    LaunchedEffect(Unit) {
        viewModel.submitted.collect { currentOnSubmit() }
    }

    if (uiState.showCellularPrompt) {
        CellularConfirmDialog(
            onConfirm = viewModel::confirmCellularSubmit,
            onDismiss = viewModel::dismissCellularPrompt
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Review Report") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // Video preview
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black)
            ) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply { player = exoPlayer }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            Column(modifier = Modifier.padding(16.dp)) {
                Text("Report Details", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        MetadataRow("Recorded At", formatTimestamp(recordingStartedAt))
                        HorizontalDivider()
                        MetadataRow("Duration", formatDuration(durationMs))
                        HorizontalDivider()
                        MetadataRow("File Size", FileUtil(context).formatFileSize(trimmedFile.length()))
                        if (location != null) {
                            HorizontalDivider()
                            MetadataRow("Latitude", "%.6f°".format(location.latitude))
                            HorizontalDivider()
                            MetadataRow("Longitude", "%.6f°".format(location.longitude))
                            HorizontalDivider()
                            MetadataRow("GPS Accuracy", "±%.0f m".format(location.accuracy))
                        } else {
                            HorizontalDivider()
                            MetadataRow("Location", "Not available")
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))

                Button(
                    onClick = viewModel::submit,
                    enabled = !uiState.isSubmitting,
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Text("Submit Report") }

                Spacer(Modifier.height(8.dp))

                OutlinedButton(
                    onClick = onRetrim,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Re-trim") }

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun MetadataRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

private fun formatTimestamp(epochMs: Long): String =
    SimpleDateFormat("dd MMM yyyy, HH:mm:ss", Locale.getDefault()).format(Date(epochMs))

private fun formatDuration(ms: Long): String {
    val sec = ms / 1000
    return if (sec >= 60) "%d min %d sec".format(sec / 60, sec % 60)
    else "$sec sec"
}
