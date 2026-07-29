package com.trafficwatch.server.geo

import org.springframework.data.jpa.repository.JpaRepository
import java.math.BigDecimal
import java.util.UUID

interface OsmLookupCacheRepository : JpaRepository<OsmLookupCache, UUID> {
    fun findByLatBucketAndLonBucket(latBucket: BigDecimal, lonBucket: BigDecimal): OsmLookupCache?
}
