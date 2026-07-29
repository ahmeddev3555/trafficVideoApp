package com.trafficwatch.server.geo

/**
 * Result of [StreetDirectionResolver.resolve]. Deliberately distinguishes [Unknown] (a way
 * was found near the point, but it carries no `oneway` tag at all) from [TwoWay] (the way is
 * explicitly tagged `oneway=no`) - OSM's data quality in this region means "no tag" cannot
 * be safely assumed to mean "legally two-way," so callers must treat [Unknown] the same as
 * "insufficient data," not as a confirmed two-way street.
 *
 * [LookupFailed] is the only variant [StreetDirectionResolver] does not cache - a transient
 * Nominatim/Overpass outage should not poison the cache with a wrong answer for the next
 * report at the same coordinate bucket.
 */
sealed class DirectionResolution {
    object NotFound : DirectionResolution()
    data class Unknown(val streetName: String?) : DirectionResolution()
    data class TwoWay(val streetName: String?) : DirectionResolution()
    data class OneWay(val streetName: String?, val legalBearingDegrees: Double) : DirectionResolution()
    data class LookupFailed(val reason: String) : DirectionResolution()
}
