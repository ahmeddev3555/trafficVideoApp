package com.trafficwatch.app.core.domain.usecase

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import com.trafficwatch.app.core.data.remote.dto.SampleJson
import com.trafficwatch.app.core.data.repository.ReportRepository
import com.trafficwatch.app.core.domain.model.LocationData
import com.trafficwatch.app.core.domain.model.Report
import com.trafficwatch.app.core.domain.model.ReportStatus
import com.trafficwatch.app.core.domain.model.RotationSample
import com.trafficwatch.app.core.util.NetworkMonitor
import com.trafficwatch.app.feature.upload.UploadWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [effectiveLocation] is the location actually persisted on the [Report] row (falls back to
 * a zeroed [LocationData] when the caller has none). [SubmitReportUseCase.confirmCellular]
 * re-reads it (and every other field) straight off the persisted row, so callers no longer
 * need to hold onto this for re-enqueueing.
 */
data class SubmitReportResult(
    val reportId: String,
    val effectiveLocation: LocationData,
    val onWifi: Boolean
)

/**
 * Creates a brand-new [Report] row (status [ReportStatus.UPLOADING]) and enqueues its upload,
 * always Wi-Fi-only first - so the report is never lost even if the caller never resolves the
 * cellular-data prompt this triggers when off Wi-Fi (see [confirmCellular]).
 */
@Singleton
class SubmitReportUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val reportRepository: ReportRepository,
    private val networkMonitor: NetworkMonitor
) {
    suspend operator fun invoke(
        trimmedFile: File,
        location: LocationData?,
        locationSamples: List<LocationData>,
        rotationSamples: List<RotationSample>,
        recordingStartedAt: Long,
        durationMs: Long
    ): SubmitReportResult {
        val reportId = UUID.randomUUID().toString()
        val effectiveLocation = location ?: LocationData(0.0, 0.0, 0f, 0.0, 0f, 0f, recordingStartedAt)

        // Serialize each captured series exactly once, here. The same strings are persisted on
        // the report row and put on the wire, so a first upload and any later retry/cellular
        // re-enqueue transmit byte-identical data.
        val locationSamplesJson = SampleJson.location(locationSamples)
        val rotationSamplesJson = SampleJson.rotation(rotationSamples)

        reportRepository.saveReport(
            Report(
                id = reportId,
                videoPath = trimmedFile.absolutePath,
                location = effectiveLocation,
                recordingStartedAt = recordingStartedAt,
                durationMs = durationMs,
                fileSizeBytes = trimmedFile.length(),
                status = ReportStatus.UPLOADING,
                locationSamplesJson = locationSamplesJson,
                rotationSamplesJson = rotationSamplesJson,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        )

        enqueue(
            reportId, trimmedFile.absolutePath, effectiveLocation, locationSamplesJson, rotationSamplesJson, recordingStartedAt, durationMs,
            requireWifiOnly = true, policy = ExistingWorkPolicy.KEEP
        )

        return SubmitReportResult(reportId, effectiveLocation, onWifi = networkMonitor.isOnWifi())
    }

    /**
     * User explicitly confirmed uploading [reportId] over cellular data. The report is
     * already [ReportStatus.UPLOADING] from [invoke], so unlike [com.trafficwatch.app.core.domain.usecase.RetryUploadUseCase]
     * there is no status to re-set here - just a re-enqueue with a relaxed network constraint.
     */
    suspend fun confirmCellular(reportId: String) {
        val row = reportRepository.getReport(reportId) ?: run {
            // The row was written by invoke() moments ago; a miss means local DB corruption.
            // The Wi-Fi-only enqueue from invoke() still stands, so the report is not lost -
            // don't crash (see docs/improvements-backlog.md: ReviewViewModel.submit() error handling).
            return
        }
        enqueue(
            reportId, row.videoPath, row.location, row.locationSamplesJson, row.rotationSamplesJson,
            row.recordingStartedAt, row.durationMs,
            requireWifiOnly = false, policy = ExistingWorkPolicy.REPLACE
        )
    }

    private fun enqueue(
        reportId: String,
        videoPath: String,
        location: LocationData,
        locationSamplesJson: String?,
        rotationSamplesJson: String?,
        recordingStartedAt: Long,
        durationMs: Long,
        requireWifiOnly: Boolean,
        policy: ExistingWorkPolicy
    ) {
        val request = UploadWorker.buildRequest(
            reportId, videoPath, location, locationSamplesJson, rotationSamplesJson, recordingStartedAt, durationMs, requireWifiOnly
        )
        WorkManager.getInstance(context)
            .enqueueUniqueWork(UploadWorker.uniqueWorkName(reportId), policy, request)
    }
}
