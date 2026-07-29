package com.trafficwatch.server.videoanalysis

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient

/**
 * Unlike [com.trafficwatch.server.geo.OsmClientConfig]'s two `RestClient` beans, this one is
 * built from the Spring Boot-autoconfigured [RestClient.Builder] (injected, not
 * `RestClient.builder()`) - the Python service's JSON is snake_case by design, matching this
 * app's own global Jackson `SNAKE_CASE` naming strategy exactly, so no separate ObjectMapper
 * is needed here.
 */
@Configuration
class VideoAnalysisClientConfig(
    private val videoAnalysisProperties: VideoAnalysisProperties,
) {

    @Bean("videoAnalysisRestClient")
    fun videoAnalysisRestClient(builder: RestClient.Builder): RestClient {
        val requestFactory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(videoAnalysisProperties.connectTimeoutMs)
            setReadTimeout(videoAnalysisProperties.readTimeoutMs)
        }

        return builder
            .baseUrl(videoAnalysisProperties.baseUrl)
            .requestFactory(requestFactory)
            .build()
    }
}
