package com.trafficwatch.app.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trafficwatch.app.core.data.repository.AuthRepository
import com.trafficwatch.app.core.data.repository.ReportRepository
import com.trafficwatch.app.core.domain.model.Report
import com.trafficwatch.app.core.domain.usecase.GetReportStatusUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HistoryUiState(
    val isRefreshing: Boolean = false
)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val reportRepository: ReportRepository,
    private val authRepository: AuthRepository,
    private val getReportStatusUseCase: GetReportStatusUseCase
) : ViewModel() {

    val reports = reportRepository.observeReports()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState = _uiState.asStateFlow()

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

    fun logout() {
        authRepository.logout()
    }
}
