package com.trafficwatch.server.geo

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

/**
 * One corridor-consensus flow observed in one report's clip: "at this ~11m
 * lat/lon bucket, [vehicleCount] vehicles were seen flowing along
 * [bearingDegrees] with tightness [resultantLength]". Ingestion rules (>=2
 * vehicles, R >= threshold, evaluated candidate never included) live in
 * FlowObservationService - only qualifying consensuses ever become rows.
 */
@Entity
@Table(name = "flow_observations")
class FlowObservation(
    @Column(name = "lat_bucket", nullable = false)
    var latBucket: BigDecimal,

    @Column(name = "lon_bucket", nullable = false)
    var lonBucket: BigDecimal,

    @Column(name = "bearing_degrees", nullable = false)
    var bearingDegrees: BigDecimal,

    @Column(name = "vehicle_count", nullable = false)
    var vehicleCount: Int,

    @Column(name = "resultant_length", nullable = false)
    var resultantLength: BigDecimal,

    @Column(name = "reporter_id", nullable = false)
    var reporterId: UUID,

    @Column(name = "report_id", nullable = false)
    var reportId: UUID,

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    var id: UUID? = null,
)
