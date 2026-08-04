package com.trafficwatch.app.core.domain.usecase

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
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
 * a zeroed [LocationData] when the caller has none) - callers re-enqueueing later via
 * [SubmitReportUseCase.confirmCellular] must reuse this value rather than recompute the
 * fallback themselves.
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

        reportRepository.saveReport(
            Report(
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
        )

        enqueue(
            reportId, trimmedFile.absolutePath, effectiveLocation, locationSamples, rotationSamples, recordingStartedAt, durationMs,
            requireWifiOnly = true, policy = ExistingWorkPolicy.KEEP
        )

        return SubmitReportResult(reportId, effectiveLocation, onWifi = networkMonitor.isOnWifi())
    }

    /**
     * User explicitly confirmed uploading [reportId] over cellular data. The report is
     * already [ReportStatus.UPLOADING] from [invoke], so unlike [com.trafficwatch.app.core.domain.usecase.RetryUploadUseCase]
     * there is no status to re-set here - just a re-enqueue with a relaxed network constraint.
     */
    suspend fun confirmCellular(
        reportId: String,
        videoPath: String,
        location: LocationData,
        locationSamples: List<LocationData>,
        rotationSamples: List<RotationSample>,
        recordingStartedAt: Long,
        durationMs: Long
    ) {
        enqueue(
            reportId, videoPath, location, locationSamples, rotationSamples, recordingStartedAt, durationMs,
            requireWifiOnly = false, policy = ExistingWorkPolicy.REPLACE
        )
    }

    private fun enqueue(
        reportId: String,
        videoPath: String,
        location: LocationData,
        locationSamples: List<LocationData>,
        rotationSamples: List<RotationSample>,
        recordingStartedAt: Long,
        durationMs: Long,
        requireWifiOnly: Boolean,
        policy: ExistingWorkPolicy
    ) {
        // Serialization (and omitting the field entirely when the list is empty - the same
        // "presence, not sentinel" convention as compass heading) happens inside
        // UploadWorker.buildInputData, not here - keeps this use case free of a Gson/DTO
        // dependency and matches how compassHeadingDegrees is threaded through unconverted.
        val request = UploadWorker.buildRequest(
            reportId, videoPath, location, locationSamples, rotationSamples, recordingStartedAt, durationMs, requireWifiOnly
        )
        WorkManager.getInstance(context)
            .enqueueUniqueWork(UploadWorker.uniqueWorkName(reportId), policy, request)
    }
}
