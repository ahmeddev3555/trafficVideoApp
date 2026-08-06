package com.trafficwatch.app.feature.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import coil.compose.AsyncImage
import com.trafficwatch.app.BuildConfig
import com.trafficwatch.app.core.domain.model.Report
import com.trafficwatch.app.core.domain.model.ReportStatus
import com.trafficwatch.app.core.ui.components.LocationMapView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportDetailScreen(
    reportId: String,
    onNavigateBack: () -> Unit,
    getReport: suspend (String) -> Report?
) {
    var report by remember { mutableStateOf<Report?>(null) }

    LaunchedEffect(reportId) {
        report = getReport(reportId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Report Details") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        val r = report
        if (r == null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Status banner
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Status", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Text(r.status.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        if (r.analysisMessage != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(r.analysisMessage, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (r.licensePlate != null) {
                            Spacer(Modifier.height(8.dp))
                            DetailRow("License Plate", r.licensePlate)
                        }
                        if (r.confidence != null) {
                            DetailRow("Plate Read Confidence", "%.0f%%".format(r.confidence * 100))
                        }
                        if (r.wrongWayConfidence != null) {
                            DetailRow("Wrong-Way Confidence", "${(r.wrongWayConfidence * 100).roundToInt()}%")
                        }
                    }
                }

                // Metadata
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Metadata", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        HorizontalDivider()
                        DetailRow("Recorded At", formatTs(r.recordingStartedAt))
                        HorizontalDivider()
                        DetailRow("Duration", "${r.durationMs / 1000} sec")
                        HorizontalDivider()
                        DetailRow("Latitude", "%.6f°".format(r.location.latitude))
                        HorizontalDivider()
                        DetailRow("Longitude", "%.6f°".format(r.location.longitude))
                        HorizontalDivider()
                        DetailRow("GPS Accuracy", "±%.0f m".format(r.location.accuracy))
                        if (r.serverId != null) {
                            HorizontalDivider()
                            DetailRow("Report ID", r.serverId)
                        }
                    }
                }

                // The map pin and flagged-vehicle image only make sense once a violation has
                // actually been confirmed - PENDING/REJECTED reports keep the layout above
                // unchanged.
                if (r.status == ReportStatus.CONFIRMED) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Location", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(8.dp))
                            LocationMapView(
                                latitude = r.location.latitude,
                                longitude = r.location.longitude,
                                modifier = Modifier.fillMaxWidth().height(150.dp)
                            )
                        }
                    }

                    if (r.hasWrongWayFrame && r.serverId != null) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Flagged Vehicle", style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(8.dp))
                                AsyncImage(
                                    model = "${BuildConfig.BASE_URL}reports/${r.serverId}/wrong-way-frame",
                                    contentDescription = "Flagged vehicle, wrong-way direction highlighted in red",
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }

                // Debug builds only: the full evidence/score breakdown behind this
                // report's outcome, for threshold tuning. Release builds never
                // render this (and the data is harmless if present - it is the
                // user's own report's analysis detail).
                if (BuildConfig.DEBUG && r.evidenceBreakdownJson != null) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Score Breakdown (debug)", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                prettyJson(r.evidenceBreakdownJson),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

private fun formatTs(epochMs: Long) =
    SimpleDateFormat("dd MMM yyyy, HH:mm:ss", Locale.getDefault()).format(Date(epochMs))

private fun prettyJson(json: String): String = try {
    GsonBuilder().setPrettyPrinting().create().toJson(JsonParser.parseString(json))
} catch (e: Exception) {
    json
}
