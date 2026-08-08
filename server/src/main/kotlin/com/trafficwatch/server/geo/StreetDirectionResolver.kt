package com.trafficwatch.server.geo

import com.trafficwatch.server.geo.dto.OverpassElement
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

private const val DIVIDED_CARRIAGEWAY_ANTI_PARALLEL_TOLERANCE_DEGREES = 45.0
private const val DIVIDED_CARRIAGEWAY_MAX_DISTANCE_GAP_METERS = 30.0

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
) {

    fun resolve(latitude: BigDecimal, longitude: BigDecimal, accuracyMeters: Double): DirectionResolution {
        val latBucket = roundToBucket(latitude)
        val lonBucket = roundToBucket(longitude)
        val searchRadius = computeSearchRadius(
            accuracyMeters,
            osmProperties.searchRadiusMeters.toDouble(),
            osmProperties.radiusAccuracyMultiplier,
            osmProperties.maxSearchRadiusMeters.toDouble(),
        )

        cacheRepository.findByLatBucketAndLonBucket(latBucket, lonBucket)?.let {
            return it.toDirectionResolution()
        }

        val resolution = try {
            resolveFresh(latitude.toDouble(), longitude.toDouble(), searchRadius, accuracyMeters)
        } catch (ex: OsmLookupException) {
            return DirectionResolution.LookupFailed(ex.message ?: "OSM lookup failed")
        }

        persist(latBucket, lonBucket, resolution)
        return resolution
    }

    private fun resolveFresh(lat: Double, lon: Double, searchRadius: Double, accuracyMeters: Double): DirectionResolution {
        val ways = overpassClient.findNearbyWays(lat, lon, searchRadius)
        val point = GeoPoint(lat, lon)

        val candidates = ways.mapNotNull { way ->
            val nodes = way.geometry?.map { GeoPoint(it.lat, it.lon) } ?: return@mapNotNull null
            val segmentIndex = BearingMath.nearestSegmentIndex(point, nodes) ?: return@mapNotNull null
            val distance = BearingMath.distanceToSegmentMeters(point, nodes[segmentIndex], nodes[segmentIndex + 1])
            WayCandidate(way, nodes, segmentIndex, distance)
        }.sortedBy { it.distanceMeters }

        val best = candidates.firstOrNull() ?: return DirectionResolution.NotFound
        val streetName = best.way.tags?.get("name") ?: reverseGeocodeStreetName(lat, lon)

        val runnerUp = candidates.getOrNull(1)
        if (runnerUp != null) {
            val bestNameTag = best.way.tags?.get("name")
            val runnerUpNameTag = runnerUp.way.tags?.get("name")
            val sameStreet = bestNameTag != null && runnerUpNameTag != null && bestNameTag == runnerUpNameTag
            if (!sameStreet && (runnerUp.distanceMeters - best.distanceMeters) < accuracyMeters) {
                return DirectionResolution.Unknown(streetName)
            }
        }

        val resolution = when (best.way.tags?.get("oneway")) {
            "yes", "true", "1" -> DirectionResolution.OneWay(
                streetName,
                BearingMath.initialBearingDegrees(best.nodes[best.segmentIndex], best.nodes[best.segmentIndex + 1]),
            )
            "-1", "reverse" -> DirectionResolution.OneWay(
                streetName,
                BearingMath.initialBearingDegrees(best.nodes[best.segmentIndex + 1], best.nodes[best.segmentIndex]),
            )
            "no" -> DirectionResolution.TwoWay(streetName)
            // Tag absent or an unrecognized value: OSM coverage here is too sparse to
            // safely assume "no tag" means "legally two-way" - see DirectionResolution's
            // doc comment.
            else -> DirectionResolution.Unknown(streetName)
        }

        if (resolution is DirectionResolution.OneWay && hasAntiParallelOneWayNeighbor(best, candidates)) {
            return DirectionResolution.Unknown(streetName)
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
        val onewayTags = setOf("yes", "true", "1", "-1", "reverse")
        fun legalBearing(candidate: WayCandidate): Double = when (candidate.way.tags?.get("oneway")) {
            "-1", "reverse" -> BearingMath.initialBearingDegrees(candidate.nodes[candidate.segmentIndex + 1], candidate.nodes[candidate.segmentIndex])
            else -> BearingMath.initialBearingDegrees(candidate.nodes[candidate.segmentIndex], candidate.nodes[candidate.segmentIndex + 1])
        }
        val bestBearing = legalBearing(best)

        return candidates.any { other ->
            other !== best &&
                other.way.tags?.get("oneway") in onewayTags &&
                kotlin.math.abs(other.distanceMeters - best.distanceMeters) <= DIVIDED_CARRIAGEWAY_MAX_DISTANCE_GAP_METERS &&
                BearingMath.angularDifferenceDegrees(legalBearing(other), bestBearing) > (180.0 - DIVIDED_CARRIAGEWAY_ANTI_PARALLEL_TOLERANCE_DEGREES)
        }
    }

    private fun reverseGeocodeStreetName(lat: Double, lon: Double): String? =
        try {
            nominatimClient.reverseGeocode(lat, lon).address?.road
        } catch (ex: OsmLookupException) {
            null
        }

    private fun roundToBucket(value: BigDecimal): BigDecimal = value.setScale(4, RoundingMode.HALF_UP)

    private fun persist(latBucket: BigDecimal, lonBucket: BigDecimal, resolution: DirectionResolution) {
        val entity = OsmLookupCache(
            latBucket = latBucket,
            lonBucket = lonBucket,
            streetName = resolution.streetNameOrNull(),
            directionState = resolution.toDirectionState(),
            legalBearingDegrees = (resolution as? DirectionResolution.OneWay)
                ?.legalBearingDegrees
                ?.toBigDecimal(),
        )
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
        DirectionState.UNKNOWN -> DirectionResolution.Unknown(streetName)
        DirectionState.TWO_WAY -> DirectionResolution.TwoWay(streetName)
        DirectionState.ONE_WAY -> DirectionResolution.OneWay(
            streetName,
            requireNotNull(legalBearingDegrees) { "ONE_WAY cache row must have a legal bearing" }.toDouble(),
        )
    }
}

private fun Double.toBigDecimal(): BigDecimal = BigDecimal.valueOf(this)

/**
 * Search radius scaled from the report's GPS accuracy - wider accuracy uncertainty means a
 * wider net is needed to have any chance of including the true street as a candidate.
 * Clamped between [floorMeters] (today's fixed default, so a very precise fix still gets a
 * sane minimum search area) and [capMeters] (bounds Overpass query cost/latency for a very
 * poor GPS fix).
 */
internal fun computeSearchRadius(accuracyMeters: Double, floorMeters: Double, multiplier: Double, capMeters: Double): Double =
    (accuracyMeters * multiplier).coerceIn(floorMeters, capMeters)
