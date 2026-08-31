package com.trafficwatch.server.geo

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * Binds `app.osm.*` configuration. Not a secret, so every field carries a sane default
 * (mirrors `com.trafficwatch.server.storage.StorageProperties`'s reasoning) - except that
 * [userAgent]'s default is a placeholder, not a real one: OSM's usage policy requires an
 * identifying User-Agent with real contact info before any production traffic is sent.
 */
@Component
@ConfigurationProperties(prefix = "app.osm")
data class OsmProperties(
    var nominatimBaseUrl: String = "https://nominatim.openstreetmap.org",
    var overpassBaseUrls: List<String> = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
    ),
    // Per-endpoint attempts inside OverpassClient's mirror loop. Cross-endpoint redundancy
    // replaces most same-endpoint retrying, so this is 1 by default (lookupRetryAttempts
    // still governs NominatimClient).
    var overpassPerEndpointAttempts: Int = 1,
    var userAgent: String = "TrafficWatch-Server/1.0 (set a real contact in your environment)",
    var connectTimeoutMs: Int = 5000,
    var readTimeoutMs: Int = 8000,
    var searchRadiusMeters: Int = 50,
    var maxSearchRadiusMeters: Int = 200,
    var radiusAccuracyMultiplier: Double = 2.0,
    // A transient failure (network error, or a 5xx from the upstream API) is retried this
    // many times total before giving up - a 4xx is never retried, see withOsmRetry.
    var lookupRetryAttempts: Int = 3,
    var lookupRetryDelayMs: Long = 500,
)
