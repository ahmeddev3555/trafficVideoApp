package com.trafficwatch.server.geo

import com.trafficwatch.server.geo.dto.OverpassElement
import com.trafficwatch.server.geo.dto.OverpassResponse
import com.trafficwatch.server.geo.dto.OverpassResult
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.client.body
import java.net.URI

/**
 * Queries every configured Overpass mirror in sequence and unions the ways, so one stale
 * replica of the public API cannot determine a resolution. Ways with a `highway` tag within
 * a radius; deduped by way id (keeping the richer geometry on a rare id collision).
 */
@Component
class OverpassClient(
    @Qualifier("overpassRestClient") private val restClient: RestClient,
    private val osmProperties: OsmProperties,
) {
    private val logger = LoggerFactory.getLogger(OverpassClient::class.java)

    /**
     * StreetDirectionResolver downgrades any `OneWay` backed by fewer than two distinct
     * sources to `Unknown(NOT_CROSS_CHECKED)`, so a single-mirror configuration silently
     * disables one-way resolution entirely. Say so once, loudly, at startup.
     */
    @PostConstruct
    fun warnIfSingleMirror() {
        val distinctUrls = osmProperties.overpassBaseUrls.distinct()
        if (distinctUrls.size < 2) {
            logger.warn(
                "app.osm.overpass-base-urls has only {} distinct endpoint(s) - OSM one-way " +
                    "resolution is DISABLED (every OneWay downgrades to Unknown/NOT_CROSS_CHECKED) " +
                    "until a second distinct Overpass mirror is configured",
                distinctUrls.size,
            )
        }
    }

    fun findNearbyWays(lat: Double, lon: Double, radiusMeters: Double): OverpassResult {
        val query = """
            [out:json];
            way(around:$radiusMeters,$lat,$lon)["highway"];
            out geom;
        """.trimIndent()
        val formBody = LinkedMultiValueMap<String, String>().apply { add("data", query) }

        val byId = LinkedHashMap<Long, OverpassElement>()
        var sourceCount = 0

        // distinct() so sourceCount counts DISTINCT endpoints: the same URL listed twice is
        // one mirror, and must never be able to satisfy the cross-check threshold.
        for (url in osmProperties.overpassBaseUrls.distinct()) {
            val host = runCatching { URI(url).host }.getOrNull() ?: url
            val elements = try {
                queryEndpoint(url, formBody)
            } catch (ex: OsmLookupException) {
                logger.warn("Overpass endpoint {} failed, skipping: {}", host, ex.message)
                continue
            }
            sourceCount++
            logger.info(
                "Overpass endpoint {} returned {} ways: {}",
                host, elements.size, elements.mapNotNull { it.id },
            )
            for (el in elements) {
                val id = el.id ?: continue
                val existing = byId[id]
                if (existing == null || (el.geometry?.size ?: 0) > (existing.geometry?.size ?: 0)) {
                    byId[id] = el
                }
            }
        }

        if (sourceCount == 0) {
            throw OsmLookupException("Every Overpass endpoint failed", isRetryable = true)
        }
        return OverpassResult(ways = byId.values.toList(), sourceCount = sourceCount)
    }

    private fun queryEndpoint(url: String, formBody: LinkedMultiValueMap<String, String>): List<OverpassElement> {
        val perEndpointProps = osmProperties.copy(
            lookupRetryAttempts = osmProperties.overpassPerEndpointAttempts,
        )
        return withOsmRetry(perEndpointProps) {
            try {
                val response = restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(formBody)
                    .retrieve()
                    .body<OverpassResponse>()
                    ?: throw OsmLookupException("Overpass lookup returned an empty body")
                response.elements
            } catch (ex: RestClientResponseException) {
                throw OsmLookupException(
                    "Overpass lookup failed with HTTP ${ex.statusCode}",
                    ex,
                    isRetryable = ex.statusCode.is5xxServerError(),
                )
            } catch (ex: RestClientException) {
                throw OsmLookupException("Overpass lookup failed", ex)
            }
        }
    }
}
