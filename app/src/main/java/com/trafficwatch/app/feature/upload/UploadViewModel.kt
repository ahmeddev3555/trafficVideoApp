package com.trafficwatch.app.feature.upload

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.trafficwatch.app.core.data.repository.ReportRepository
import com.trafficwatch.app.core.domain.model.LocationData
import com.trafficwatch.app.core.domain.model.Report
import com.trafficwatch.app.core.domain.model.ReportStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject

sealed class UploadState {
    object Queued : UploadState()
    data class Uploading(val progressPercent: Int) : UploadState()
    object Success : UploadState()
    data class Failed(val message: String) : UploadState()
}

data class UploadUiState(
    val uploadState: UploadState = UploadState.Queued
)

@HiltViewModel
class UploadViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val reportRepository: ReportRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(UploadUiState())
    val uiState = _uiState.asStateFlow()

    private var workId: UUID? = null

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

        val inputData = UploadWorker.buildInputData(
            reportId = reportId,
            videoPath = trimmedFile.absolutePath,
            location = effectiveLocation,
            recordingStartedAt = recordingStartedAt,
            durationMs = durationMs
        )

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(inputData)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .build()

        workId = request.id
        WorkManager.getInstance(context).enqueue(request)
        observeUploadWork(request.id)
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
