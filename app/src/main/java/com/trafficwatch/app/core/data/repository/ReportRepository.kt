package com.trafficwatch.app.core.data.repository

import com.trafficwatch.app.core.data.local.dao.ReportDao
import com.trafficwatch.app.core.data.local.entity.ReportEntity
import com.trafficwatch.app.core.data.remote.ApiService
import com.trafficwatch.app.core.domain.model.Report
import com.trafficwatch.app.core.domain.model.ReportStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReportRepository @Inject constructor(
    private val reportDao: ReportDao,
    private val apiService: ApiService
) {
    fun observeReports(): Flow<List<Report>> =
        reportDao.observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun getReport(id: String): Report? =
        reportDao.getById(id)?.toDomain()

    suspend fun saveReport(report: Report) =
        reportDao.insert(ReportEntity.fromDomain(report))

    suspend fun updateReport(report: Report) =
        reportDao.update(ReportEntity.fromDomain(report))

    suspend fun deleteReport(id: String) =
        reportDao.deleteById(id)

    suspend fun updateStatus(id: String, status: ReportStatus, serverId: String?) =
        reportDao.updateStatus(id, status.name, serverId, System.currentTimeMillis())

    /** Polls the remote API and refreshes all non-terminal reports in the DB. */
    suspend fun syncPendingReports() {
        val pending = reportDao.getPendingRemoteReports()
        pending.forEach { entity ->
            val serverId = entity.serverId ?: return@forEach
            runCatching {
                val response = apiService.getReportStatus(serverId)
                val status = ReportStatus.valueOf(response.status)
                reportDao.updateAnalysisResult(
                    id = entity.id,
                    status = status.name,
                    licensePlate = response.licensePlate,
                    confidence = response.confidence,
                    message = response.message,
                    hasWrongWayFrame = response.hasWrongWayFrame,
                    wrongWayConfidence = response.wrongWayConfidence,
                    updatedAt = System.currentTimeMillis()
                )
            }
        }
    }
}
