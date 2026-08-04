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
    var lastException: OsmLookupException? = null
    repeat(properties.lookupRetryAttempts) { attempt ->
        try {
            return call()
        } catch (ex: OsmLookupException) {
            lastException = ex
            if (!ex.isRetryable || attempt == properties.lookupRetryAttempts - 1) throw ex
            Thread.sleep(properties.lookupRetryDelayMs)
        }
    }
    throw requireNotNull(lastException)
}
