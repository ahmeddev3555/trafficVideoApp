package com.trafficwatch.app.feature.trim

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, androidx.media3.common.util.UnstableApi::class)
@Composable
fun TrimScreen(
    rawVideoFile: File,
    onTrimComplete: (trimmedFile: File) -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: TrimViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
        }
    }

    // Initialise video metadata
    LaunchedEffect(rawVideoFile.absolutePath) {
        val retriever = android.media.MediaMetadataRetriever()
        retriever.setDataSource(rawVideoFile.absolutePath)
        val durationMs = retriever.extractMetadata(
            android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
        )?.toLongOrNull() ?: 0L
        retriever.release()
        viewModel.initVideo(rawVideoFile.absolutePath, durationMs)
        exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(rawVideoFile)))
        exoPlayer.prepare()
    }

    // Handle trim completion
    LaunchedEffect(uiState.trimProgress) {
        when (val p = uiState.trimProgress) {
            is TrimProgress.Done -> onTrimComplete(p.outputFile)
            is TrimProgress.Failed -> snackbarHostState.showSnackbar("Trim failed: ${p.error}")
            else -> Unit
        }
    }

    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trim Video") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
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
                        PlayerView(ctx).apply {
                            player = exoPlayer
                            useController = true
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(Modifier.height(16.dp))

            // Duration labels
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Selected: ${formatMs(uiState.trimEndMs - uiState.trimStartMs)}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Total: ${formatMs(uiState.totalDurationMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(8.dp))

            // Trim range bar
            if (uiState.totalDurationMs > 0) {
                TrimRangeBar(
                    totalDurationMs = uiState.totalDurationMs,
                    startMs = uiState.trimStartMs,
                    endMs = uiState.trimEndMs,
                    onRangeChange = viewModel::onRangeChange,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            // Start/end timestamps
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(formatMs(uiState.trimStartMs), style = MaterialTheme.typography.labelSmall)
                Text(formatMs(uiState.trimEndMs), style = MaterialTheme.typography.labelSmall)
            }

            Spacer(Modifier.height(16.dp))

            // Trim progress indicator
            when (val progress = uiState.trimProgress) {
                is TrimProgress.Working -> {
                    Text("Trimming… ${progress.percent}%", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(
                        progress = { progress.percent / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                else -> Unit
            }

            Spacer(Modifier.weight(1f))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        exoPlayer.seekTo(uiState.trimStartMs)
                        exoPlayer.play()
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Preview") }

                Button(
                    onClick = viewModel::confirmTrim,
                    enabled = uiState.trimProgress !is TrimProgress.Working
                            && uiState.totalDurationMs > 0,
                    modifier = Modifier.weight(1f)
                ) {
                    if (uiState.trimProgress is TrimProgress.Working) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.height(18.dp)
                        )
                    } else {
                        Text("Confirm Trim")
                    }
                }
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}
