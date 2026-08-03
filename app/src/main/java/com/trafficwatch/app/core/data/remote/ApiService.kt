package com.trafficwatch.app.core.data.remote

import com.trafficwatch.app.core.data.remote.dto.AuthResponse
import com.trafficwatch.app.core.data.remote.dto.LoginRequest
import com.trafficwatch.app.core.data.remote.dto.RegisterRequest
import com.trafficwatch.app.core.data.remote.dto.ReportListResponse
import com.trafficwatch.app.core.data.remote.dto.ReportStatusResponse
import com.trafficwatch.app.core.data.remote.dto.SubmitReportResponse
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

interface ApiService {

    // ── Auth ──────────────────────────────────────────────────────────────────

    @POST("auth/register")
    suspend fun register(@Body request: RegisterRequest): AuthResponse

    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): AuthResponse

    // ── Reports ───────────────────────────────────────────────────────────────

    @Multipart
    @POST("reports")
    suspend fun submitReport(
        @Part video: MultipartBody.Part,
        @Part("latitude") latitude: RequestBody,
        @Part("longitude") longitude: RequestBody,
        @Part("accuracy") accuracy: RequestBody,
        @Part("altitude") altitude: RequestBody,
        @Part("bearing") bearing: RequestBody,
        @Part("speed") speed: RequestBody,
        @Part("recorded_at") recordedAt: RequestBody,
        @Part("duration_ms") durationMs: RequestBody,
        @Part("device_id") deviceId: RequestBody,
        @Part("compass_heading_degrees") compassHeadingDegrees: RequestBody?,
        @Part("location_samples") locationSamples: RequestBody?
    ): SubmitReportResponse

    @GET("reports/{reportId}/status")
    suspend fun getReportStatus(@Path("reportId") reportId: String): ReportStatusResponse

    @GET("reports")
    suspend fun getReports(
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 20,
        @Query("status") status: String? = null
    ): ReportListResponse
}
