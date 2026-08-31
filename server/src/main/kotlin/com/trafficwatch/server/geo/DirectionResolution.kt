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
    data class Unknown(
        val streetName: String?,
        val reason: UnknownReason = UnknownReason.NO_ONEWAY_TAG,
    ) : DirectionResolution()
    data class TwoWay(val streetName: String?) : DirectionResolution()
    data class OneWay(val streetName: String?, val legalBearingDegrees: Double) : DirectionResolution()
    data class LookupFailed(val reason: String) : DirectionResolution()
}

/**
 * Why [DirectionResolution.Unknown] was returned. Only [DIVIDED_CARRIAGEWAY] means "the
 * street IS one-way, we just can't tell which carriageway" - the one case where
 * ReportAnalysisJob's stationary-approach path may still fire (see the 2026-08-31 design).
 *
 * [NOT_CROSS_CHECKED] is, like [DirectionResolution.LookupFailed], an artifact of the
 * lookup moment rather than a fact about the street (fewer than two Overpass mirrors
 * answered), so [StreetDirectionResolver] deliberately does not cache it either.
 */
enum class UnknownReason {
    NO_ONEWAY_TAG,
    AMBIGUOUS_NEAREST_STREET,
    DIVIDED_CARRIAGEWAY,
    NOT_CROSS_CHECKED,
}
