package com.trafficwatch.app.core.data.remote

import com.trafficwatch.app.core.data.remote.dto.AuthResponse
import com.trafficwatch.app.core.data.remote.dto.LoginRequest
import com.trafficwatch.app.core.data.remote.dto.RegisterRequest
import com.trafficwatch.app.core.data.remote.dto.ReportListResponse
import com.trafficwatch.app.core.data.remote.dto.ReportStatusResponse
import com.trafficwatch.app.core.data.remote.dto.SubmitReportResponse
import com.trafficwatch.app.core.data.remote.dto.UserDto
import kotlinx.coroutines.delay
import okhttp3.MultipartBody
import okhttp3.RequestBody
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

class DuplicatePhoneNumberException(message: String) : Exception(message)

/**
 * Fake implementation of [ApiService] used while the real backend is not yet available.
 *
 * Swap this out in [com.trafficwatch.app.di.NetworkModule] once the server is live.
 */
@Singleton
class MockApiService @Inject constructor() : ApiService {

    // Simulate network latency
    private suspend fun fakeDelay() = delay(800)

    private val registeredPhoneNumbers = ConcurrentHashMap.newKeySet<String>()

    override suspend fun register(request: RegisterRequest): AuthResponse {
        fakeDelay()
        if (!registeredPhoneNumbers.add(request.phoneNumber)) {
            throw DuplicatePhoneNumberException("An account with this phone number already exists.")
        }
        return AuthResponse(
            token = "mock_token_${UUID.randomUUID()}",
            user = UserDto(
                id = UUID.randomUUID().toString(),
                name = request.name,
                email = request.email
            )
        )
    }

    override suspend fun login(request: LoginRequest): AuthResponse {
        fakeDelay()
        // Accept any credentials in mock mode
        return AuthResponse(
            token = "mock_token_${UUID.randomUUID()}",
            user = UserDto(
                id = "mock_user_1",
                name = "Demo User",
                email = request.email
            )
        )
    }

    override suspend fun submitReport(
        video: MultipartBody.Part,
        latitude: RequestBody,
        longitude: RequestBody,
        accuracy: RequestBody,
        altitude: RequestBody,
        bearing: RequestBody,
        speed: RequestBody,
        recordedAt: RequestBody,
        durationMs: RequestBody,
        deviceId: RequestBody
    ): SubmitReportResponse {
        fakeDelay()
        return SubmitReportResponse(
            reportId = UUID.randomUUID().toString(),
            status = "PENDING",
            message = "Report received and queued for analysis"
        )
    }

    override suspend fun getReportStatus(reportId: String): ReportStatusResponse {
        fakeDelay()
        return ReportStatusResponse(
            reportId = reportId,
            status = "PENDING",
            licensePlate = null,
            confidence = null,
            message = "Analysis in progress",
            updatedAt = java.time.Instant.now().toString()
        )
    }

    override suspend fun getReports(page: Int, pageSize: Int, status: String?): ReportListResponse {
        fakeDelay()
        return ReportListResponse(reports = emptyList(), total = 0, page = page)
    }
}
