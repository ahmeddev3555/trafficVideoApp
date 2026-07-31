package com.trafficwatch.server.videoanalysis

import com.trafficwatch.server.videoanalysis.dto.VideoAnalysisResponse
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.FileSystemResource
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.client.body
import java.nio.file.Path
import java.util.UUID

/**
 * Thin HTTP wrapper around the Python video-analysis service. Kept deliberately dumb on
 * this side too - just uploads the video and returns whatever vehicles come back;
 * [com.trafficwatch.server.reports.ReportAnalysisJob] applies all business logic.
 */
@Component
class VideoAnalysisClient(
    @Qualifier("videoAnalysisRestClient") private val restClient: RestClient,
    // No code default, fails application startup fast if unset - mirrors
    // com.trafficwatch.server.auth.JwtService's app.jwt.secret handling. See
    // VideoAnalysisProperties's doc comment for why this isn't just another field there.
    @Value("\${app.video-analysis.api-key}") private val apiKey: String,
) {

    /**
     * Uploads the video at [videoPath] for analysis. [reportId] is echoed/logged only by the
     * Python side. Returns the full response (vehicles plus frame dimensions) - callers such
     * as [com.trafficwatch.server.reports.ReportAnalysisJob] need `frameWidth`/`frameHeight`
     * for corridor/flow analysis, not just the vehicle list.
     */
    fun analyze(videoPath: Path, reportId: UUID): VideoAnalysisResponse {
        val body = LinkedMultiValueMap<String, Any>()
        body.add("video", FileSystemResource(videoPath))
        body.add("report_id", reportId.toString())

        try {
            return restClient.post()
                .uri("/v1/analyze")
                .header("X-API-Key", apiKey)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .body<VideoAnalysisResponse>()
                ?: throw VideoAnalysisException("Video analysis service returned an empty body")
        } catch (ex: RestClientResponseException) {
            throw VideoAnalysisException("Video analysis request failed with HTTP ${ex.statusCode}", ex)
        } catch (ex: RestClientException) {
            throw VideoAnalysisException("Video analysis request failed", ex)
        }
    }
}
