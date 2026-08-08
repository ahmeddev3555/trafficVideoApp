package com.trafficwatch.server.geo

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SearchRadiusTest {

    @Test
    fun `scales linearly with accuracy between the floor and cap`() {
        assertThat(computeSearchRadius(accuracyMeters = 80.0, floorMeters = 50.0, multiplier = 2.0, capMeters = 200.0))
            .isEqualTo(160.0)
    }

    @Test
    fun `never goes below the floor for very precise accuracy`() {
        assertThat(computeSearchRadius(accuracyMeters = 2.0, floorMeters = 50.0, multiplier = 2.0, capMeters = 200.0))
            .isEqualTo(50.0)
    }

    @Test
    fun `never exceeds the cap for very poor accuracy`() {
        assertThat(computeSearchRadius(accuracyMeters = 500.0, floorMeters = 50.0, multiplier = 2.0, capMeters = 200.0))
            .isEqualTo(200.0)
    }
}
