package com.trafficwatch.app.feature.trim

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.PlayerView
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, androidx.media3.common.util.UnstableApi::class)
@Composable
fun TrimScreen(
    rawVideoFile: File,
    recordingStartedAt: Long,
    onTrimComplete: (trimmedFile: File) -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: TrimViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // Defaults to landscape until the real video size is known; updated to the true
    // (rotation-corrected) display aspect ratio once ExoPlayer reports it, so portrait
    // clips get a properly-shaped preview instead of being squeezed into a 16:9 box.
    var videoAspectRatio by remember { mutableFloatStateOf(16f / 9f) }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            addListener(object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize.height != 0) {
                        videoAspectRatio = videoSize.width.toFloat() / videoSize.height
                    }
                }
            })
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

    // Freeze on the scrubbed frame while a handle is being dragged. Seeking uses the
    // nearest sync (key) frame instead of an exact decode while scrubbing, since exact
    // seeks are too slow to keep up with rapid drag events on a local file — the frame
    // shown during a drag is just a visual aid and never affects the actual trim points.
    LaunchedEffect(uiState.scrubPositionMs) {
        val positionMs = uiState.scrubPositionMs
        if (positionMs != null) {
            exoPlayer.setSeekParameters(SeekParameters.CLOSEST_SYNC)
            exoPlayer.pause()
            exoPlayer.seekTo(positionMs)
        } else {
            exoPlayer.setSeekParameters(SeekParameters.EXACT)
        }
    }

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
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            // Reserve space for everything below the preview (duration labels, scrub bar +
            // minimap when a long clip needs them, time-of-day row, spacer, action buttons),
            // sized for the worst case (scrub bar + minimap both present) so the preview's
            // height budget is computed from actual available space rather than a guessed
            // percentage — the goal is everything fits on one screen without scrolling.
            val reservedControlsHeight = 280.dp
            val maxPreviewHeight = (maxHeight - reservedControlsHeight).coerceAtLeast(160.dp)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            // Video preview — sized to the video's real (rotation-corrected) aspect ratio,
            // "contained" within the height budget above so portrait clips shrink in width
            // instead of being stretched into a landscape-shaped box. Landscape clips are
            // unaffected since their natural height already falls under the cap. The Column
            // still scrolls as a safety net in case the estimate above is off on some device.
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val widthForCappedHeight = maxPreviewHeight * videoAspectRatio
                val previewWidth = if (widthForCappedHeight <= maxWidth) widthForCappedHeight else maxWidth
                val previewHeight = previewWidth / videoAspectRatio

                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .width(previewWidth)
                        .height(previewHeight)
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

                    if (uiState.scrubPositionMs != null) {
                        Text(
                            "Dragging…",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(6.dp)
                                .background(Color.Black.copy(alpha = 0.6f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Duration labels
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Selected: ${formatMs(uiState.selectedDurationMs)}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Total: ${formatMs(uiState.totalDurationMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(8.dp))

            // Fixed 5s window scrub bar — only needed when there's actually something to
            // scrub through; a clip already shorter than the max is entirely selected.
            if (uiState.totalDurationMs > uiState.maxDurationMs) {
                TrimWindowScrubBar(
                    totalDurationMs = uiState.totalDurationMs,
                    windowStartMs = uiState.trimStartMs,
                    windowDurationMs = uiState.selectedDurationMs,
                    recordingStartedAt = recordingStartedAt,
                    onWindowPositionChange = viewModel::onWindowPositionChange,
                    onScrubChange = viewModel::onScrubChange,
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                Spacer(Modifier.height(4.dp))

                TrimMinimap(
                    totalDurationMs = uiState.totalDurationMs,
                    windowStartMs = uiState.trimStartMs,
                    windowDurationMs = uiState.selectedDurationMs,
                    onWindowPositionChange = viewModel::onWindowPositionChange
                )
            } else if (uiState.totalDurationMs > 0) {
                Text(
                    "Entire clip selected",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Start/end time-of-day
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    formatTimeOfDay(recordingStartedAt + uiState.trimStartMs),
                    style = MaterialTheme.typography.labelSmall
                )
                Text(
                    formatTimeOfDay(recordingStartedAt + uiState.trimEndMs),
                    style = MaterialTheme.typography.labelSmall
                )
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

            Spacer(Modifier.height(24.dp))

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
}

private fun formatMs(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}
