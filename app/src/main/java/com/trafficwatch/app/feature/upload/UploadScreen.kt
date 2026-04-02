package com.trafficwatch.app.feature.upload

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trafficwatch.app.core.domain.model.LocationData
import java.io.File

@Composable
fun UploadScreen(
    trimmedFile: File,
    location: LocationData?,
    recordingStartedAt: Long,
    durationMs: Long,
    onUploadSuccess: () -> Unit,
    onRetry: () -> Unit,
    viewModel: UploadViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(trimmedFile.absolutePath) {
        viewModel.startUpload(trimmedFile, location, recordingStartedAt, durationMs)
    }

    LaunchedEffect(uiState.uploadState) {
        if (uiState.uploadState is UploadState.Success) {
            kotlinx.coroutines.delay(1500)
            onUploadSuccess()
        }
    }

    Scaffold { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.padding(32.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                when (val state = uiState.uploadState) {
                    is UploadState.Queued, is UploadState.Uploading -> {
                        val pct = if (state is UploadState.Uploading) state.progressPercent else 0

                        CircularProgressIndicator(modifier = Modifier.size(72.dp))
                        Text("Uploading Report…", style = MaterialTheme.typography.titleMedium)

                        if (pct > 0) {
                            LinearProgressIndicator(
                                progress = { pct / 100f },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text("$pct%", style = MaterialTheme.typography.bodySmall)
                        }

                        OutlinedButton(onClick = viewModel::cancelUpload) {
                            Text("Cancel")
                        }
                    }

                    is UploadState.Success -> {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(72.dp)
                        )
                        Text(
                            "Report Submitted!",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Your report is queued for analysis. We'll notify you when it's reviewed.",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onUploadSuccess, modifier = Modifier.fillMaxWidth()) {
                            Text("View My Reports")
                        }
                    }

                    is UploadState.Failed -> {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(72.dp)
                        )
                        Text(
                            "Upload Failed",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                            Text("Retry")
                        }
                    }
                }
            }
        }
    }
}
