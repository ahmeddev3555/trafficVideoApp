package com.trafficwatch.server.geo

/**
 * Thrown by [NominatimClient]/[OverpassClient] on any HTTP/network/parsing failure, so
 * [StreetDirectionResolver] is the single place that decides fallback behavior
 * ([DirectionResolution.LookupFailed]) instead of each client swallowing errors differently.
 */
class OsmLookupException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
