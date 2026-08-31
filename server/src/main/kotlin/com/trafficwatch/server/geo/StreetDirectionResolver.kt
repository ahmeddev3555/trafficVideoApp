package com.trafficwatch.server.geo

import com.trafficwatch.server.geo.dto.OverpassElement
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.OffsetDateTime

private const val DIVIDED_CARRIAGEWAY_ANTI_PARALLEL_TOLERANCE_DEGREES = 45.0
private const val DIVIDED_CARRIAGEWAY_MAX_DISTANCE_GAP_METERS = 30.0

private val ONEWAY_FORWARD_VALUES = setOf("yes", "true", "1")
private val ONEWAY_REVERSE_VALUES = setOf("-1", "reverse")

private data class WayCandidate(
    val way: OverpassElement,
    val nodes: List<GeoPoint>,
    val segmentIndex: Int,
    val distanceMeters: Double,
)

/**
 * Resolves the street and legal traffic direction nearest a report's coordinates, backed by
 * a lat/lon-bucketed cache table. Always returns a [DirectionResolution] - never throws - so
 * [com.trafficwatch.server.reports.ReportAnalysisJob] doesn't need any OSM-specific
 * exception handling of its own.
 */
@Component
class StreetDirectionResolver(
    private val nominatimClient: NominatimClient,
    private val overpassClient: OverpassClient,
    private val cacheRepository: OsmLookupCacheRepository,
    private val osmProperties: OsmProperties,
    private val clock: Clock,
) {

    private val logger = LoggerFactory.getLogger(StreetDirectionResolver::class.java)

    fun resolve(latitude: BigDecimal, longitude: BigDecimal, accuracyMeters: Double): DirectionResolution {
        val clampedAccuracy = accuracyMeters.coerceIn(1.0, osmProperties.maxSearchRadiusMeters.toDouble())
        val latBucket = roundToBucket(latitude)
        val lonBucket = roundToBucket(longitude)
        val searchRadius = computeSearchRadius(
            clampedAccuracy,
            osmProperties.searchRadiusMeters.toDouble(),
            osmProperties.radiusAccuracyMultiplier,
            osmProperties.maxSearchRadiusMeters.toDouble(),
        )

        val cached = cacheRepository.findByLatBucketAndLonBucket(latBucket, lonBucket)
        val cacheFresh = cached != null &&
            cached.updatedAt.isAfter(OffsetDateTime.now(clock).minusDays(osmProperties.cacheTtlDays))
        if (cacheFresh && cached!!.searchRadiusMeters.toDouble() >= searchRadius &&
            cached.accuracyMeters.toDouble() >= clampedAccuracy
        ) {
            return cached.toDirectionResolution()
        }

        val resolution = try {
            resolveFresh(latitude.toDouble(), longitude.toDouble(), searchRadius, clampedAccuracy)
        } catch (ex: OsmLookupException) {
            return DirectionResolution.LookupFailed(ex.message ?: "OSM lookup failed")
        }

        persist(cached, latBucket, lonBucket, searchRadius, clampedAccuracy, resolution)
        return resolution
    }

    private fun resolveFresh(lat: Double, lon: Double, searchRadius: Double, accuracyMeters: Double): DirectionResolution {
        val overpass = overpassClient.findNearbyWays(lat, lon, searchRadius)
        val ways = overpass.ways
        val point = GeoPoint(lat, lon)

        val candidates = ways.mapNotNull { way ->
            val nodes = way.geometry?.map { GeoPoint(it.lat, it.lon) } ?: return@mapNotNull null
            val segmentIndex = BearingMath.nearestSegmentIndex(point, nodes) ?: return@mapNotNull null
            val distance = BearingMath.distanceToSegmentMeters(point, nodes[segmentIndex], nodes[segmentIndex + 1])
            WayCandidate(way, nodes, segmentIndex, distance)
        }.sortedBy { it.distanceMeters }

        val best = candidates.firstOrNull() ?: return DirectionResolution.NotFound
        val streetName = best.way.tags?.get("name") ?: reverseGeocodeStreetName(lat, lon)

        val bestNameTag = best.way.tags?.get("name")
        val nearestDifferentStreet = candidates.drop(1).firstOrNull { candidate ->
            val nameTag = candidate.way.tags?.get("name")
            !(bestNameTag != null && nameTag != null && bestNameTag == nameTag)
        }
        if (nearestDifferentStreet != null && (nearestDifferentStreet.distanceMeters - best.distanceMeters) < accuracyMeters) {
            return DirectionResolution.Unknown(streetName, UnknownReason.AMBIGUOUS_NEAREST_STREET)
        }

        val resolution = when (best.way.tags?.get("oneway")) {
            in ONEWAY_FORWARD_VALUES -> DirectionResolution.OneWay(
                streetName,
                BearingMath.initialBearingDegrees(best.nodes[best.segmentIndex], best.nodes[best.segmentIndex + 1]),
            )
            in ONEWAY_REVERSE_VALUES -> DirectionResolution.OneWay(
                streetName,
                BearingMath.initialBearingDegrees(best.nodes[best.segmentIndex + 1], best.nodes[best.segmentIndex]),
            )
            "no" -> DirectionResolution.TwoWay(streetName)
            // Tag absent or an unrecognized value: OSM coverage here is too sparse to
            // safely assume "no tag" means "legally two-way" - see DirectionResolution's
            // doc comment.
            else -> DirectionResolution.Unknown(streetName, UnknownReason.NO_ONEWAY_TAG)
        }

        if (resolution is DirectionResolution.OneWay && hasAntiParallelOneWayNeighbor(best, candidates)) {
            return DirectionResolution.Unknown(streetName, UnknownReason.DIVIDED_CARRIAGEWAY)
        }

        if (resolution is DirectionResolution.OneWay && overpass.sourceCount < 2) {
            logger.warn(
                "Overpass OneWay from a single un-cross-checked source at {},{} - downgrading to Unknown",
                lat, lon,
            )
            return DirectionResolution.Unknown(streetName, UnknownReason.NOT_CROSS_CHECKED)
        }
        return resolution
    }

    /**
     * True when another candidate way, also tagged `oneway`, has a legal bearing anti-parallel
     * to [best]'s (within [DIVIDED_CARRIAGEWAY_ANTI_PARALLEL_TOLERANCE_DEGREES] of exactly
     * 180 degrees apart) and sits within [DIVIDED_CARRIAGEWAY_MAX_DISTANCE_GAP_METERS] of
     * [best]'s own distance to the point - the physical signature of a divided road's two
     * separately-tagged, oppositely-legal carriageways.
     */
    private fun hasAntiParallelOneWayNeighbor(best: WayCandidate, candidates: List<WayCandidate>): Boolean {
        fun legalBearing(candidate: WayCandidate): Double = when (candidate.way.tags?.get("oneway")) {
            in ONEWAY_REVERSE_VALUES -> BearingMath.initialBearingDegrees(candidate.nodes[candidate.segmentIndex + 1], candidate.nodes[candidate.segmentIndex])
            else -> BearingMath.initialBearingDegrees(candidate.nodes[candidate.segmentIndex], candidate.nodes[candidate.segmentIndex + 1])
        }
        val bestBearing = legalBearing(best)

        return candidates.any { other ->
            other !== best &&
                other.way.tags?.get("oneway") in (ONEWAY_FORWARD_VALUES + ONEWAY_REVERSE_VALUES) &&
                BearingMath.distanceToSegmentMeters(segmentMidpoint(best), other.nodes[other.segmentIndex], other.nodes[other.segmentIndex + 1]) <= DIVIDED_CARRIAGEWAY_MAX_DISTANCE_GAP_METERS &&
                BearingMath.angularDifferenceDegrees(legalBearing(other), bestBearing) > (180.0 - DIVIDED_CARRIAGEWAY_ANTI_PARALLEL_TOLERANCE_DEGREES)
        }
    }

    private fun segmentMidpoint(candidate: WayCandidate): GeoPoint {
        val a = candidate.nodes[candidate.segmentIndex]
        val b = candidate.nodes[candidate.segmentIndex + 1]
        return GeoPoint((a.latitude + b.latitude) / 2.0, (a.longitude + b.longitude) / 2.0)
    }

    private fun reverseGeocodeStreetName(lat: Double, lon: Double): String? =
        try {
            nominatimClient.reverseGeocode(lat, lon).address?.road
        } catch (ex: OsmLookupException) {
            null
        }

    private fun roundToBucket(value: BigDecimal): BigDecimal = value.setScale(4, RoundingMode.HALF_UP)

    private fun persist(
        existing: OsmLookupCache?,
        latBucket: BigDecimal,
        lonBucket: BigDecimal,
        searchRadiusMeters: Double,
        accuracyMeters: Double,
        resolution: DirectionResolution,
    ) {
        val entity = existing ?: OsmLookupCache(
            latBucket = latBucket,
            lonBucket = lonBucket,
            searchRadiusMeters = searchRadiusMeters.toBigDecimal(),
            accuracyMeters = accuracyMeters.toBigDecimal(),
            directionState = resolution.toDirectionState(),
        )
        entity.searchRadiusMeters = searchRadiusMeters.toBigDecimal()
        entity.accuracyMeters = accuracyMeters.toBigDecimal()
        entity.streetName = resolution.streetNameOrNull()
        entity.directionState = resolution.toDirectionState()
        entity.unknownReason = (resolution as? DirectionResolution.Unknown)?.reason
        entity.legalBearingDegrees = (resolution as? DirectionResolution.OneWay)
            ?.legalBearingDegrees
            ?.toBigDecimal()
        entity.updatedAt = OffsetDateTime.now(clock)
        cacheRepository.save(entity)
    }

    private fun DirectionResolution.streetNameOrNull(): String? = when (this) {
        is DirectionResolution.Unknown -> streetName
        is DirectionResolution.TwoWay -> streetName
        is DirectionResolution.OneWay -> streetName
        is DirectionResolution.NotFound -> null
        is DirectionResolution.LookupFailed -> null
    }

    private fun DirectionResolution.toDirectionState(): DirectionState = when (this) {
        is DirectionResolution.NotFound -> DirectionState.NOT_FOUND
        is DirectionResolution.Unknown -> DirectionState.UNKNOWN
        is DirectionResolution.TwoWay -> DirectionState.TWO_WAY
        is DirectionResolution.OneWay -> DirectionState.ONE_WAY
        is DirectionResolution.LookupFailed ->
            throw IllegalStateException("LookupFailed must never be persisted to the cache")
    }

    private fun OsmLookupCache.toDirectionResolution(): DirectionResolution = when (directionState) {
        DirectionState.NOT_FOUND -> DirectionResolution.NotFound
        DirectionState.UNKNOWN -> DirectionResolution.Unknown(
            streetName,
            unknownReason ?: UnknownReason.NO_ONEWAY_TAG,
        )
        DirectionState.TWO_WAY -> DirectionResolution.TwoWay(streetName)
        DirectionState.ONE_WAY -> DirectionResolution.OneWay(
            streetName,
            requireNotNull(legalBearingDegrees) { "ONE_WAY cache row must have a legal bearing" }.toDouble(),
        )
    }
}

private fun Double.toBigDecimal(): BigDecimal = BigDecimal.valueOf(this).setScale(2, RoundingMode.CEILING)

/**
 * Search radius scaled from the report's GPS accuracy - wider accuracy uncertainty means a
 * wider net is needed to have any chance of including the true street as a candidate.
 * Clamped between [floorMeters] (today's fixed default, so a very precise fix still gets a
 * sane minimum search area) and [capMeters] (bounds Overpass query cost/latency for a very
 * poor GPS fix).
 */
internal fun computeSearchRadius(accuracyMeters: Double, floorMeters: Double, multiplier: Double, capMeters: Double): Double =
    (accuracyMeters * multiplier).coerceIn(floorMeters, capMeters)
