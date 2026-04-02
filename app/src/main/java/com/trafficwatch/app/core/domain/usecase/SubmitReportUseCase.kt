package com.trafficwatch.app.core.domain.usecase

import com.trafficwatch.app.core.data.repository.ReportRepository
import com.trafficwatch.app.core.domain.model.Report
import javax.inject.Inject

class SubmitReportUseCase @Inject constructor(
    private val reportRepository: ReportRepository
) {
    suspend operator fun invoke(report: Report) = reportRepository.saveReport(report)
}
