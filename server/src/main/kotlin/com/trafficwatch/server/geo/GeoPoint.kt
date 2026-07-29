package com.trafficwatch.server.geo

/** A plain lat/lon pair in degrees, used only for the pure math in [BearingMath]. */
data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
)
