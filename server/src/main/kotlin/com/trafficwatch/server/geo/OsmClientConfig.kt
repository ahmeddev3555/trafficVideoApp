package com.trafficwatch.server.geo

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.web.client.RestClient

/**
 * Two separate [RestClient] beans (one per upstream OSM API), each with its own Jackson
 * [ObjectMapper] rather than the app-wide SNAKE_CASE-configured one bound in
 * `application.yml` (`spring.jackson.property-naming-strategy`) - Nominatim/Overpass field
 * naming isn't under our control and doesn't reliably follow either convention, so DTOs in
 * `geo.dto` declare their own camelCase-matching or explicitly-annotated property names.
 *
 * `FAIL_ON_UNKNOWN_PROPERTIES` is disabled since both upstream APIs return far more fields
 * than this app cares about.
 */
@Configuration
class OsmClientConfig(
    private val osmProperties: OsmProperties,
) {

    @Bean("nominatimRestClient")
    fun nominatimRestClient(): RestClient = buildClient(osmProperties.nominatimBaseUrl)

    @Bean("overpassRestClient")
    fun overpassRestClient(): RestClient = buildClient(osmProperties.overpassBaseUrl)

    private fun buildClient(baseUrl: String): RestClient {
        val objectMapper = ObjectMapper()
            .registerKotlinModule()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        val converter = MappingJackson2HttpMessageConverter(objectMapper)

        val requestFactory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(osmProperties.connectTimeoutMs)
            setReadTimeout(osmProperties.readTimeoutMs)
        }

        return RestClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader(HttpHeaders.USER_AGENT, osmProperties.userAgent)
            .requestFactory(requestFactory)
            .messageConverters { converters ->
                converters.removeIf { it is MappingJackson2HttpMessageConverter }
                converters.add(0, converter)
            }
            .build()
    }
}
