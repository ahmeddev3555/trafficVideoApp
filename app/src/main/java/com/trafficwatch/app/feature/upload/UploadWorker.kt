package com.trafficwatch.app.feature.upload

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.trafficwatch.app.core.data.remote.ApiService
import com.trafficwatch.app.core.data.repository.ReportRepository
import com.trafficwatch.app.core.domain.model.LocationData
import com.trafficwatch.app.core.domain.model.Report
import com.trafficwatch.app.core.domain.model.ReportStatus
import com.trafficwatch.app.core.util.FileUtil
import com.trafficwatch.app.core.util.TokenStore
import com.trafficwatch.app.core.util.asStreamingRequestBody
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val apiService: ApiService,
    private val reportRepository: ReportRepository,
    private val tokenStore: TokenStore,
    private val fileUtil: FileUtil
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val localReportId = inputData.getString(KEY_REPORT_ID) ?: return Result.failure()
        val videoPath = inputData.getString(KEY_VIDEO_PATH) ?: return Result.failure()
        val latitude = inputData.getDouble(KEY_LATITUDE, 0.0)
        val longitude = inputData.getDouble(KEY_LONGITUDE, 0.0)
        val accuracy = inputData.getFloat(KEY_ACCURACY, 0f)
        val altitude = inputData.getDouble(KEY_ALTITUDE, 0.0)
        val bearing = inputData.getFloat(KEY_BEARING, 0f)
        val speed = inputData.getFloat(KEY_SPEED, 0f)
        val recordedAt = inputData.getLong(KEY_RECORDED_AT, 0L)
        val durationMs = inputData.getLong(KEY_DURATION_MS, 0L)

        val videoFile = File(videoPath)
        if (!videoFile.exists()) return Result.failure(workDataOf("error" to "Video file not found"))

        setProgress(workDataOf(KEY_PROGRESS to 0))

        return try {
            val videoPart = MultipartBody.Part.createFormData(
                "video",
                videoFile.name,
                videoFile.asStreamingRequestBody("video/mp4".toMediaType())
            )

            val isoDate = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date(recordedAt))

            val response = apiService.submitReport(
                video = videoPart,
                latitude = latitude.toString().toRequestBody(),
                longitude = longitude.toString().toRequestBody(),
                accuracy = accuracy.toString().toRequestBody(),
                altitude = altitude.toString().toRequestBody(),
                bearing = bearing.toString().toRequestBody(),
                speed = speed.toString().toRequestBody(),
                recordedAt = isoDate.toRequestBody(),
                durationMs = durationMs.toString().toRequestBody(),
                deviceId = tokenStore.getOrCreateDeviceId().toRequestBody()
            )

            setProgress(workDataOf(KEY_PROGRESS to 100))

            reportRepository.updateStatus(localReportId, ReportStatus.PENDING, response.reportId)

            // Clean up local video file after successful upload
            fileUtil.deleteFile(videoPath)

            Result.success(workDataOf(KEY_SERVER_ID to response.reportId))
        } catch (e: Exception) {
            reportRepository.updateStatus(localReportId, ReportStatus.UPLOAD_FAILED, null)
            if (runAttemptCount < 3) Result.retry()
            else Result.failure(workDataOf("error" to (e.message ?: "Upload failed")))
        }
    }

    companion object {
        const val KEY_REPORT_ID = "report_id"
        const val KEY_VIDEO_PATH = "video_path"
        const val KEY_LATITUDE = "latitude"
        const val KEY_LONGITUDE = "longitude"
        const val KEY_ACCURACY = "accuracy"
        const val KEY_ALTITUDE = "altitude"
        const val KEY_BEARING = "bearing"
        const val KEY_SPEED = "speed"
        const val KEY_RECORDED_AT = "recorded_at"
        const val KEY_DURATION_MS = "duration_ms"
        const val KEY_PROGRESS = "progress"
        const val KEY_SERVER_ID = "server_id"

        fun buildInputData(
            reportId: String,
            videoPath: String,
            location: LocationData,
            recordingStartedAt: Long,
            durationMs: Long
        ) = workDataOf(
            KEY_REPORT_ID to reportId,
            KEY_VIDEO_PATH to videoPath,
            KEY_LATITUDE to location.latitude,
            KEY_LONGITUDE to location.longitude,
            KEY_ACCURACY to location.accuracy,
            KEY_ALTITUDE to location.altitude,
            KEY_BEARING to location.bearing,
            KEY_SPEED to location.speed,
            KEY_RECORDED_AT to recordingStartedAt,
            KEY_DURATION_MS to durationMs
        )
    }
}
