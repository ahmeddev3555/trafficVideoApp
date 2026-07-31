package com.trafficwatch.server.geo

import org.springframework.data.jpa.repository.JpaRepository
import java.math.BigDecimal
import java.util.UUID

interface FlowObservationRepository : JpaRepository<FlowObservation, UUID> {
    fun findByLatBucketAndLonBucket(latBucket: BigDecimal, lonBucket: BigDecimal): List<FlowObservation>
}
