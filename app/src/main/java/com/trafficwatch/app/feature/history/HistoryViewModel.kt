package com.trafficwatch.app.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.trafficwatch.app.core.data.repository.AuthRepository
import com.trafficwatch.app.core.data.repository.ReportRepository
import com.trafficwatch.app.core.domain.model.Report
import com.trafficwatch.app.core.domain.model.ReportStatus
import com.trafficwatch.app.core.domain.usecase.GetReportStatusUseCase
import com.trafficwatch.app.core.domain.usecase.RetryUploadResult
import com.trafficwatch.app.core.domain.usecase.RetryUploadUseCase
import com.trafficwatch.app.feature.upload.UploadWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HistoryUiState(
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val pendingCellularReport: Report? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val reportRepository: ReportRepository,
    private val authRepository: AuthRepository,
    private val getReportStatusUseCase: GetReportStatusUseCase,
    private val retryUploadUseCase: RetryUploadUseCase,
    private val workManager: WorkManager
) : ViewModel() {

    data class UploadProgress(
        val bytesUploaded: Long,
        val totalBytes: Long,
        val bytesPerSecond: Long
    )

    val reports = reportRepository.observeReports()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState = _uiState.asStateFlow()

    val uploadProgress: StateFlow<Map<String, UploadProgress>> = reports
        .map { list -> list.filter { it.status == ReportStatus.UPLOADING }.map { it.id } }
        .distinctUntilChanged()
        .flatMapLatest { uploadingIds ->
            if (uploadingIds.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(
                    uploadingIds.map { id ->
                        workManager.getWorkInfosForUniqueWorkFlow(UploadWorker.uniqueWorkName(id))
                            .map { workInfos -> id to workInfos.firstOrNull()?.toUploadProgress() }
                    }
                ) { pairs -> pairs.mapNotNull { (id, progress) -> progress?.let { id to it } }.toMap() }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private fun WorkInfo.toUploadProgress(): UploadProgress? {
        val bytesUploaded = progress.getLong(UploadWorker.KEY_BYTES_UPLOADED, -1L)
        val totalBytes = progress.getLong(UploadWorker.KEY_TOTAL_BYTES, -1L)
        val bytesPerSecond = progress.getLong(UploadWorker.KEY_BYTES_PER_SECOND, 0L)
        return if (bytesUploaded >= 0 && totalBytes > 0) {
            UploadProgress(bytesUploaded, totalBytes, bytesPerSecond)
        } else {
            null
        }
    }

    init {
        startPolling()
    }

    private fun startPolling() {
        viewModelScope.launch {
            getReportStatusUseCase().collect { /* triggers DB update via repository */ }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            reportRepository.syncPendingReports()
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    fun deleteReport(report: Report) {
        viewModelScope.launch {
            reportRepository.deleteReport(report.id)
        }
    }

    fun retryUpload(report: Report) {
        viewModelScope.launch {
            when (val result = retryUploadUseCase(report)) {
                is RetryUploadResult.VideoMissing ->
                    _uiState.update { it.copy(error = "Video file is no longer available on this device") }
                is RetryUploadResult.Enqueued ->
                    if (!result.onWifi) _uiState.update { it.copy(pendingCellularReport = report) }
            }
        }
    }

    fun confirmCellularRetry() {
        val report = _uiState.value.pendingCellularReport ?: return
        viewModelScope.launch {
            retryUploadUseCase(report, forceCellular = true)
            _uiState.update { it.copy(pendingCellularReport = null) }
        }
    }

    fun dismissCellularPrompt() {
        _uiState.update { it.copy(pendingCellularReport = null) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun logout() {
        authRepository.logout()
    }
}
