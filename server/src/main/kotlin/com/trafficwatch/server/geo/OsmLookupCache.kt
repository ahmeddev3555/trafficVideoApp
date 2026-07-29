package com.trafficwatch.server.geo

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

/** Mirrors the `direction_state` CHECK constraint in `V4__add_street_name_and_osm_cache.sql`. */
enum class DirectionState {
    NOT_FOUND,
    UNKNOWN,
    TWO_WAY,
    ONE_WAY,
}

/**
 * Non-spatial cache of [StreetDirectionResolver] lookups, keyed by lat/lon rounded to 4
 * decimals (~11m grid) - see the plan's "No PostGIS" decision. [DirectionResolution.LookupFailed]
 * results are never persisted here (a transient upstream outage shouldn't poison the cache).
 */
@Entity
@Table(name = "osm_lookup_cache")
class OsmLookupCache(
    @Column(name = "lat_bucket", nullable = false)
    var latBucket: BigDecimal,

    @Column(name = "lon_bucket", nullable = false)
    var lonBucket: BigDecimal,

    @Column(name = "street_name")
    var streetName: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "direction_state", nullable = false, length = 20)
    var directionState: DirectionState,

    @Column(name = "legal_bearing_degrees")
    var legalBearingDegrees: BigDecimal? = null,

    @Column(name = "osm_way_id")
    var osmWayId: Long? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now(),

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    var id: UUID? = null,
)
