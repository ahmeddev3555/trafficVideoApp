package com.trafficwatch.app.core.domain.usecase

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import com.trafficwatch.app.core.data.repository.ReportRepository
import com.trafficwatch.app.core.domain.model.Report
import com.trafficwatch.app.core.domain.model.ReportStatus
import com.trafficwatch.app.core.util.FileUtil
import com.trafficwatch.app.core.util.NetworkMonitor
import com.trafficwatch.app.feature.upload.UploadWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

sealed class RetryUploadResult {
    object VideoMissing : RetryUploadResult()
    data class Enqueued(val onWifi: Boolean) : RetryUploadResult()
}

/**
 * Re-enqueues an upload for a report stuck in [ReportStatus.UPLOADING]/[ReportStatus.UPLOAD_FAILED]
 * (serverId still null, so it's invisible to [ReportRepository.syncPendingReports]) using data
 * already persisted on the [Report] row - no re-recording required, as long as the source video
 * file (deleted only on a successful upload) is still present on disk.
 */
@Singleton
class RetryUploadUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val reportRepository: ReportRepository,
    private val fileUtil: FileUtil,
    private val networkMonitor: NetworkMonitor
) {
    suspend operator fun invoke(report: Report, forceCellular: Boolean = false): RetryUploadResult {
        if (!fileUtil.exists(report.videoPath)) return RetryUploadResult.VideoMissing

        reportRepository.updateStatus(report.id, ReportStatus.UPLOADING, null)

        val onWifi = networkMonitor.isOnWifi()
        // The persisted Report row has no locationSamples field (Task 1 only threaded the
        // continuous GPS series through the transient ReviewUiState, not into local storage),
        // so a retry has nothing to resend here - an empty list omits the field entirely per
        // the "presence, not sentinel" convention in UploadWorker.buildInputData.
        val request = UploadWorker.buildRequest(
            report.id, report.videoPath, report.location, emptyList(),
            report.recordingStartedAt, report.durationMs,
            requireWifiOnly = !forceCellular
        )
        val policy = if (forceCellular) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        WorkManager.getInstance(context)
            .enqueueUniqueWork(UploadWorker.uniqueWorkName(report.id), policy, request)

        return RetryUploadResult.Enqueued(onWifi = forceCellular || onWifi)
    }
}
