package com.trafficwatch.server.geo

import com.trafficwatch.server.geo.dto.OverpassElement
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

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
            resolveFresh(latitude.toDouble(), longitude.toDouble(), searchRadius)
        } catch (ex: OsmLookupException) {
            return DirectionResolution.LookupFailed(ex.message ?: "OSM lookup failed")
        }

        persist(latBucket, lonBucket, resolution)
        return resolution
    }

    private fun resolveFresh(lat: Double, lon: Double, searchRadius: Double): DirectionResolution {
        val ways = overpassClient.findNearbyWays(lat, lon, searchRadius)
        if (ways.isEmpty()) {
            return DirectionResolution.NotFound
        }

        val point = GeoPoint(lat, lon)
        var bestWay: OverpassElement? = null
        var bestNodes: List<GeoPoint>? = null
        var bestSegmentIndex = -1
        var bestDistance = Double.MAX_VALUE

        for (way in ways) {
            val nodes = way.geometry?.map { GeoPoint(it.lat, it.lon) } ?: continue
            val segmentIndex = BearingMath.nearestSegmentIndex(point, nodes) ?: continue
            val distance = BearingMath.distanceToSegmentMeters(point, nodes[segmentIndex], nodes[segmentIndex + 1])
            if (distance < bestDistance) {
                bestDistance = distance
                bestWay = way
                bestNodes = nodes
                bestSegmentIndex = segmentIndex
            }
        }

        val way = bestWay ?: return DirectionResolution.NotFound
        val nodes = bestNodes ?: return DirectionResolution.NotFound
        val streetName = way.tags?.get("name") ?: reverseGeocodeStreetName(lat, lon)

        return when (way.tags?.get("oneway")) {
            "yes", "true", "1" -> DirectionResolution.OneWay(
                streetName,
                BearingMath.initialBearingDegrees(nodes[bestSegmentIndex], nodes[bestSegmentIndex + 1]),
            )
            "-1", "reverse" -> DirectionResolution.OneWay(
                streetName,
                BearingMath.initialBearingDegrees(nodes[bestSegmentIndex + 1], nodes[bestSegmentIndex]),
            )
            "no" -> DirectionResolution.TwoWay(streetName)
            // Tag absent or an unrecognized value: OSM coverage here is too sparse to
            // safely assume "no tag" means "legally two-way" - see DirectionResolution's
            // doc comment.
            else -> DirectionResolution.Unknown(streetName)
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
