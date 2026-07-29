package com.trafficwatch.server.geo

import com.trafficwatch.server.geo.dto.OverpassElement
import com.trafficwatch.server.geo.dto.OverpassResponse
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.client.body

/** Thin wrapper around the Overpass API - queries ways tagged `highway` within a radius. */
@Component
class OverpassClient(
    @Qualifier("overpassRestClient") private val restClient: RestClient,
    private val osmProperties: OsmProperties,
) {

    /** Ways with a `highway` tag within [OsmProperties.searchRadiusMeters] of [lat]/[lon]. */
    fun findNearbyWays(lat: Double, lon: Double): List<OverpassElement> {
        val query = """
            [out:json];
            way(around:${osmProperties.searchRadiusMeters},$lat,$lon)["highway"];
            out geom;
        """.trimIndent()

        val formBody = LinkedMultiValueMap<String, String>().apply {
            add("data", query)
        }

        try {
            val response = restClient.post()
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(formBody)
                .retrieve()
                .body<OverpassResponse>()
                ?: throw OsmLookupException("Overpass lookup returned an empty body")
            return response.elements
        } catch (ex: RestClientResponseException) {
            throw OsmLookupException("Overpass lookup failed with HTTP ${ex.statusCode}", ex)
        } catch (ex: RestClientException) {
            throw OsmLookupException("Overpass lookup failed", ex)
        }
    }
}
