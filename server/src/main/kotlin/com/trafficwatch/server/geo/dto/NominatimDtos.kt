package com.trafficwatch.server.geo.dto

/**
 * Minimal shape of Nominatim's `/reverse?format=jsonv2` response - only [NominatimAddress.road]
 * is used (as a fallback street name when an Overpass way has no `name` tag). Every field is
 * nullable since Nominatim's actual response carries many more fields this app ignores.
 */
data class NominatimReverseResponse(
    val address: NominatimAddress? = null,
)

data class NominatimAddress(
    val road: String? = null,
)
