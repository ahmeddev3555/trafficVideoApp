package com.trafficwatch.server.geo.dto

/**
 * Minimal shape of an Overpass `out geom` response - `geometry` returns each way node's
 * coordinates inline, avoiding a second lookup to resolve node ids to coordinates.
 */
data class OverpassResponse(
    val elements: List<OverpassElement> = emptyList(),
)

data class OverpassElement(
    val type: String? = null,
    val id: Long? = null,
    val tags: Map<String, String>? = null,
    val geometry: List<OverpassNode>? = null,
)

data class OverpassNode(
    val lat: Double,
    val lon: Double,
)
