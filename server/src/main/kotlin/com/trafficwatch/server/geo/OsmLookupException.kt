package com.trafficwatch.server.geo

/**
 * Thrown by [NominatimClient]/[OverpassClient] on any HTTP/network/parsing failure, so
 * [StreetDirectionResolver] is the single place that decides fallback behavior
 * ([DirectionResolution.LookupFailed]) instead of each client swallowing errors differently.
 *
 * [isRetryable] distinguishes a transient failure (no HTTP response at all, or a 5xx) - worth
 * retrying via [withOsmRetry] - from a 4xx, which indicates a malformed request and would
 * never succeed on retry.
 */
class OsmLookupException(
    message: String,
    cause: Throwable? = null,
    val isRetryable: Boolean = true,
) : RuntimeException(message, cause)
