package com.trafficwatch.server.geo

import com.trafficwatch.server.geo.dto.NominatimReverseResponse
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.client.body
import org.springframework.web.client.RestClient

/** Thin wrapper around Nominatim's reverse-geocoding endpoint. */
@Component
class NominatimClient(
    @Qualifier("nominatimRestClient") private val restClient: RestClient,
    private val osmProperties: OsmProperties,
) {

    /** Reverse-geocodes [lat]/[lon] to an address (used only for its `road` field). */
    fun reverseGeocode(lat: Double, lon: Double): NominatimReverseResponse = withOsmRetry(osmProperties) {
        try {
            restClient.get()
                .uri { builder ->
                    builder.path("/reverse")
                        .queryParam("format", "jsonv2")
                        .queryParam("lat", lat)
                        .queryParam("lon", lon)
                        .build()
                }
                .retrieve()
                .body<NominatimReverseResponse>()
                ?: throw OsmLookupException("Nominatim reverse geocode returned an empty body")
        } catch (ex: RestClientResponseException) {
            throw OsmLookupException(
                "Nominatim reverse geocode failed with HTTP ${ex.statusCode}",
                ex,
                isRetryable = ex.statusCode.is5xxServerError(),
            )
        } catch (ex: RestClientException) {
            throw OsmLookupException("Nominatim reverse geocode failed", ex)
        }
    }
}
