package com.trafficwatch.server.reports

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * Binds `app.analysis.*` configuration for [ReportAnalysisJob]. Includes direction validation
 * thresholds ([wrongWayToleranceDegrees], [agreementToleranceDegrees]), evidence-fusion
 * controls ([confirmationThreshold], [weakEvidenceFloor], [consensusMinResultantLength]),
 * track-quality gating ([minDisplacementFraction]), and learned-history maturity gates
 * ([historyMinObservations], [historyMinDistinctReporters], [historyMinResultantLength]).
 */
@Component
@ConfigurationProperties(prefix = "app.analysis")
data class AnalysisProperties(
    var wrongWayToleranceDegrees: Double = 60.0,
    // CONFIRMED requires the final four-factor product to reach this value.
    var confirmationThreshold: Double = 0.5,
    // Two direction-evidence bearings "agree" when within this many degrees.
    var agreementToleranceDegrees: Double = 45.0,
    // Evidence sources below this confidence are dropped before fusion.
    var weakEvidenceFloor: Double = 0.2,
    // A corridor's consensus requires at least this mean resultant length R.
    var consensusMinResultantLength: Double = 0.6,
    // A track's displacement must clear this fraction of its OWN bounding-box diagonal to
    // count as real motion rather than detection jitter - scaled to the vehicle's own
    // apparent size so nearby (large-in-frame) and distant (small-in-frame) vehicles are
    // held to a comparable standard.
    var minDisplacementFraction: Double = 0.15,
    // Learned-history maturity gates - ALL must hold before history testifies.
    var historyMinObservations: Int = 5,
    var historyMinDistinctReporters: Int = 3,
    var historyMinResultantLength: Double = 0.8,
)
