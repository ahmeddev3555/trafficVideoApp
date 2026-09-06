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
 * street IS one-way, we just can't tell which carriageway"; the others are two-way in OSM
 * semantics ("no oneway tag" is not "legally one-way").
 *
 * How ReportAnalysisJob's additive fallbacks treat these:
 * - stationary-approach fires for ANY [DirectionResolution.Unknown] reason (2026-09-06
 *   widening) - its own corroboration gate (stationary camera, lone strong grower,
 *   >=5-member R>=0.9 receding consensus) is the safeguard, not the reason restriction.
 * - counter-flow fires for [DirectionResolution.OneWay] and [DIVIDED_CARRIAGEWAY] at the
 *   base gate (flow_coherence >= 0.6, forwardStream >= 5); every OTHER `Unknown` reason
 *   only clears eligibility behind the strong gate (flow_coherence >= 0.85 AND
 *   forwardStream >= 8), since "N forward + 1 oncoming" is legal two-way traffic there.
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
