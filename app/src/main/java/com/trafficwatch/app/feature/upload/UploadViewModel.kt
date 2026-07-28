package com.trafficwatch.app.feature.upload

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.trafficwatch.app.core.data.repository.ReportRepository
import com.trafficwatch.app.core.domain.model.LocationData
import com.trafficwatch.app.core.domain.model.Report
import com.trafficwatch.app.core.domain.model.ReportStatus
import com.trafficwatch.app.core.util.NetworkMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import javax.inject.Inject

sealed class UploadState {
    object Queued : UploadState()
    data class Uploading(val progressPercent: Int) : UploadState()
    object Success : UploadState()
    data class Failed(val message: String) : UploadState()
}

data class UploadUiState(
    val uploadState: UploadState = UploadState.Queued,
    val showCellularPrompt: Boolean = false
)

@HiltViewModel
class UploadViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val reportRepository: ReportRepository,
    private val networkMonitor: NetworkMonitor
) : ViewModel() {

    private val _uiState = MutableStateFlow(UploadUiState())
    val uiState = _uiState.asStateFlow()

    private var workId: UUID? = null

    // Stashed so a later explicit cellular-upload confirmation can rebuild the same
    // request with a relaxed network constraint, without re-running the whole
    // record->trim->review pipeline.
    private var lastReportId: String? = null
    private var lastVideoPath: String? = null
    private var lastLocation: LocationData? = null
    private var lastRecordingStartedAt: Long = 0L
    private var lastDurationMs: Long = 0L

    fun startUpload(
        trimmedFile: File,
        location: LocationData?,
        recordingStartedAt: Long,
        durationMs: Long
    ) {
        val reportId = UUID.randomUUID().toString()
        val effectiveLocation = location ?: LocationData(0.0, 0.0, 0f, 0.0, 0f, 0f, recordingStartedAt)

        // Save draft report to Room first
        viewModelScope.launch {
            val report = Report(
                id = reportId,
                videoPath = trimmedFile.absolutePath,
                location = effectiveLocation,
                recordingStartedAt = recordingStartedAt,
                durationMs = durationMs,
                fileSizeBytes = trimmedFile.length(),
                status = ReportStatus.UPLOADING,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            reportRepository.saveReport(report)
        }

        lastReportId = reportId
        lastVideoPath = trimmedFile.absolutePath
        lastLocation = effectiveLocation
        lastRecordingStartedAt = recordingStartedAt
        lastDurationMs = durationMs

        // Always enqueue with the safe Wi-Fi-only default first, so the report is never
        // lost even if the cellular-confirmation prompt below is ignored/dismissed - it
        // will simply run automatically once Wi-Fi becomes available.
        val request = UploadWorker.buildRequest(
            reportId, trimmedFile.absolutePath, effectiveLocation,
            recordingStartedAt, durationMs, requireWifiOnly = true
        )
        workId = request.id
        WorkManager.getInstance(context)
            .enqueueUniqueWork(UploadWorker.uniqueWorkName(reportId), ExistingWorkPolicy.KEEP, request)
        observeUploadWork(request.id)

        if (!networkMonitor.isOnWifi()) {
            _uiState.update { it.copy(showCellularPrompt = true) }
        }
    }

    /** User explicitly confirmed uploading over cellular data for the current attempt. */
    fun confirmCellularUpload() {
        val reportId = lastReportId ?: return
        val videoPath = lastVideoPath ?: return
        val location = lastLocation ?: return

        val request = UploadWorker.buildRequest(
            reportId, videoPath, location, lastRecordingStartedAt, lastDurationMs, requireWifiOnly = false
        )
        workId = request.id
        WorkManager.getInstance(context)
            .enqueueUniqueWork(UploadWorker.uniqueWorkName(reportId), ExistingWorkPolicy.REPLACE, request)
        observeUploadWork(request.id)
        _uiState.update { it.copy(showCellularPrompt = false) }
    }

    fun dismissCellularPrompt() {
        _uiState.update { it.copy(showCellularPrompt = false) }
    }

    private fun observeUploadWork(id: UUID) {
        viewModelScope.launch {
            WorkManager.getInstance(context).getWorkInfoByIdFlow(id).collect { info ->
                when (info?.state) {
                    WorkInfo.State.RUNNING -> {
                        val progress = info.progress.getInt(UploadWorker.KEY_PROGRESS, 0)
                        _uiState.update { it.copy(uploadState = UploadState.Uploading(progress)) }
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        _uiState.update { it.copy(uploadState = UploadState.Success) }
                    }
                    WorkInfo.State.FAILED -> {
                        val error = info.outputData.getString("error") ?: "Upload failed"
                        _uiState.update { it.copy(uploadState = UploadState.Failed(error)) }
                    }
                    else -> Unit
                }
            }
        }
    }

    fun cancelUpload() {
        workId?.let { WorkManager.getInstance(context).cancelWorkById(it) }
        _uiState.update { it.copy(uploadState = UploadState.Failed("Cancelled")) }
    }
}
