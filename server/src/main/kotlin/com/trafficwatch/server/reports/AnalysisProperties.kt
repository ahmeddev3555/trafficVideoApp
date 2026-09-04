package com.trafficwatch.server.reports

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * Binds `app.analysis.*` configuration for [ReportAnalysisJob]. Includes direction validation
 * thresholds ([wrongWayToleranceDegrees], [agreementToleranceDegrees]), evidence-fusion
 * controls ([confirmationThreshold], [weakEvidenceFloor], [consensusMinResultantLength]),
 * track-quality gating ([minDisplacementFraction]), per-candidate track-trust scoring
 * ([displacementTrustDiagonals], [scaleBearingTrustFactor]), and learned-history maturity gates
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
    // Per-candidate track-trust scoring (ClipFlowAnalyzer.FlowVehicle.trackQuality). The
    // displacement-magnitude factor saturates to 1.0 when a track has translated this many of
    // its own largest bounding-box diagonals - "moved one full vehicle-length in frame -> its
    // bearing is dominated by real motion, not centroid jitter". corridor_cohesion is
    // deliberately NOT a factor here (it double-counts against clipConfidence and penalises
    // small/fast near-camera tracks - see the 2026-09-04 design).
    // MUST stay well above minDisplacementFraction (0.15): the qualification gate already
    // guarantees displacement >= minDisplacementFraction x diagonal, so any value <= 0.15 makes
    // displacementFactor identically 1.0 for every qualifying vehicle - silently no-op-ing the
    // signal. Must be > 0 (ClipFlowAnalyzer's init enforces this).
    var displacementTrustDiagonals: Double = 1.0,
    // Multiplier applied to trackQuality for a "scale"-sourced (bbox-diagonal fallback,
    // near-head-on) bearing. 1.0 = identity; a lever if "scale" bearings prove over-generous.
    var scaleBearingTrustFactor: Double = 1.0,
    // Learned-history maturity gates - ALL must hold before history testifies.
    var historyMinObservations: Int = 5,
    var historyMinDistinctReporters: Int = 3,
    var historyMinResultantLength: Double = 0.8,
    // Stationary-approach detection (ReportAnalysisJob.tryStationaryApproachDetection):
    // a "strong grower" - a wrong-way candidate - must have grown its bbox by at least
    // this fraction, over at least this many tracked frames, with at least this detection
    // confidence. Calibrated 2026-08-30 against five real reports (see the design spec's
    // Appendix): real violators grew 0.93-2.24; every non-violator grower topped out at 0.44.
    var approachGrowthMin: Double = 0.8,
    var approachMinFrames: Int = 30,
    var approachMinDetection: Double = 0.5,
    // Stationary-approach on a DIVIDED_CARRIAGEWAY Unknown street additionally requires the
    // non-growing receding traffic to form one LARGE, TIGHTLY-COHERENT stream and the grower
    // to be a LONE anomaly: the strongest corridor consensus must have at least
    // [approachCorroborationMinMembers] members AND a mean resultant length R of at least
    // [approachCorroborationMinResultantLength], and there must be exactly one strong grower.
    // Corridor co-membership between the grower and that consensus is deliberately NOT
    // required (the median rider is tracked in its own frame-space corridor - 2026-08-31
    // production diagnostic). NOT a bearing-opposition check on the grower - the grower's
    // frame bearing is perspective-understated by construction.
    var approachCorroborationMinMembers: Int = 5,
    // Minimum mean resultant length R for that receding consensus (see above).
    var approachCorroborationMinResultantLength: Double = 0.9,
)
