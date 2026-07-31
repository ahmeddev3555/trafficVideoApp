package com.trafficwatch.server.geo

import com.trafficwatch.server.reports.AnalysisProperties
import com.trafficwatch.server.reports.Report
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/** Minimum consensus members for an observation to be worth teaching the DB. */
private const val MIN_INGEST_MEMBERS = 2

/** Curve constant: historyConfidence = (n/(n+5)) x R_hist - never reaches 1.0. */
private const val MATURITY_CURVE_CONSTANT = 5.0

/**
 * The learned per-location direction database. Ingestion is consensus-only
 * (>= 2 agreeing vehicles; the evaluated candidate is excluded upstream in
 * ClipFlowAnalyzer.corridorConsensus) so a violator - real or staged - never
 * teaches it. History testifies only at maturity: enough observations, enough
 * DISTINCT reporters (one user can never mature a location alone), and a
 * unimodal accumulated distribution. A bimodal bucket (divided road filmed
 * from one vantage, or genuinely mixed flow) yields nothing - never a
 * fabricated "two-way" verdict.
 */
@Service
class FlowObservationService(
    private val repository: FlowObservationRepository,
    private val properties: AnalysisProperties,
) {
    private val logger = LoggerFactory.getLogger(FlowObservationService::class.java)

    /**
     * Persists each qualifying corridor consensus as one observation row.
     * Never throws: a storage failure is logged and must not affect the
     * report's outcome (same error contract as frame annotation).
     */
    fun ingest(report: Report, consensuses: List<CorridorConsensus>) {
        val reportId = report.id ?: return
        for (consensus in consensuses) {
            if (consensus.memberCount < MIN_INGEST_MEMBERS) continue
            if (consensus.resultantLength < properties.consensusMinResultantLength) continue

            try {
                repository.save(
                    FlowObservation(
                        latBucket = roundToBucket(report.latitude),
                        lonBucket = roundToBucket(report.longitude),
                        bearingDegrees = BigDecimal.valueOf(consensus.bearingDegrees)
                            .setScale(2, RoundingMode.HALF_UP),
                        vehicleCount = consensus.memberCount,
                        resultantLength = BigDecimal.valueOf(consensus.resultantLength)
                            .setScale(3, RoundingMode.HALF_UP),
                        reporterId = report.userId,
                        reportId = reportId,
                    ),
                )
            } catch (ex: Exception) {
                logger.warn("FlowObservationService: failed to ingest observation for report {}", reportId, ex)
            }
        }
    }

    /**
     * The bucket's learned direction as a fusion source, or null before
     * maturity. Maturity requires ALL of: >= history-min-observations rows,
     * >= history-min-distinct-reporters distinct reporters, and
     * R_hist >= history-min-resultant-length across the rows' bearings.
     */
    fun historyEvidence(latitude: BigDecimal, longitude: BigDecimal): DirectionEvidence? {
        val rows = repository.findByLatBucketAndLonBucket(roundToBucket(latitude), roundToBucket(longitude))
        if (rows.size < properties.historyMinObservations) return null

        val distinctReporters = rows.map(FlowObservation::reporterId).toSet()
        if (distinctReporters.size < properties.historyMinDistinctReporters) return null

        val stats = BearingMath.circularStats(rows.map { it.bearingDegrees.toDouble() }) ?: return null
        if (stats.resultantLength < properties.historyMinResultantLength) return null

        val confidence = (rows.size / (rows.size + MATURITY_CURVE_CONSTANT)) * stats.resultantLength
        return DirectionEvidence(EvidenceKind.LEARNED_HISTORY, stats.meanDegrees, confidence)
    }

    /** Same 4-decimal (~11m) bucketing convention as StreetDirectionResolver. */
    private fun roundToBucket(value: BigDecimal): BigDecimal = value.setScale(4, RoundingMode.HALF_UP)
}
