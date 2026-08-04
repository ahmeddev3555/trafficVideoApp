package com.trafficwatch.server.geo

/**
 * Retries [call] on a retryable [OsmLookupException] (see its doc comment), up to
 * [OsmProperties.lookupRetryAttempts] total attempts with a fixed
 * [OsmProperties.lookupRetryDelayMs] delay between them. A non-retryable exception (a 4xx -
 * a malformed request that will never succeed) is re-thrown immediately, on the first
 * attempt. Used identically by [OverpassClient] and [NominatimClient] to avoid duplicating
 * this loop in both.
 */
fun <T> withOsmRetry(properties: OsmProperties, call: () -> T): T {
    repeat(properties.lookupRetryAttempts.coerceAtLeast(1)) { attempt ->
        try {
            return call()
        } catch (ex: OsmLookupException) {
            if (!ex.isRetryable || attempt == properties.lookupRetryAttempts.coerceAtLeast(1) - 1) throw ex
            Thread.sleep(properties.lookupRetryDelayMs)
        }
    }
    error("unreachable: the final attempt always throws")
}
