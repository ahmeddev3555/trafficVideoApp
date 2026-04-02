package com.trafficwatch.app.core.domain.usecase

import com.trafficwatch.app.core.data.repository.ReportRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject

private const val POLL_INTERVAL_MS = 30_000L

class GetReportStatusUseCase @Inject constructor(
    private val reportRepository: ReportRepository
) {
    operator fun invoke(): Flow<Unit> = flow {
        while (true) {
            reportRepository.syncPendingReports()
            emit(Unit)
            delay(POLL_INTERVAL_MS)
        }
    }
}
