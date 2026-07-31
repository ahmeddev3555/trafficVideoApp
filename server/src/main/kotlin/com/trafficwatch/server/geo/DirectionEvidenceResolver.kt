package com.trafficwatch.server.geo

import com.trafficwatch.server.reports.AnalysisProperties
import org.springframework.stereotype.Component

/**
 * The evidence sources the fusion layer understands today. Deliberately open for
 * extension: user direction-attestation (see the spec's "future source" note)
 * becomes a fourth constant here and nothing else in the interface changes.
 */
enum class EvidenceKind { OSM_TAG, CLIP_CONSENSUS, LEARNED_HISTORY }

enum class EvidenceFate { ACCEPTED, DROPPED_WEAK, CONFLICT }

data class DirectionEvidence(
    val kind: EvidenceKind,
    val bearingDegrees: Double,
    val confidence: Double,
)

/** A source plus what fusion did with it - persisted into the evidence breakdown. */
data class EvidenceEntry(
    val kind: EvidenceKind,
    val bearingDegrees: Double,
    val confidence: Double,
    val fate: EvidenceFate,
)

sealed class FusionResult {
    abstract val entries: List<EvidenceEntry>

    data class Fused(
        val bearingDegrees: Double,
        val directionConfidence: Double,
        override val entries: List<EvidenceEntry>,
    ) : FusionResult()

    data class Insufficient(
        val conflict: Boolean,
        override val entries: List<EvidenceEntry>,
    ) : FusionResult()
}

/**
 * Fuses whatever direction-evidence sources are present into one
 * (bearing, confidence) - or refuses. Rules (spec "Evidence fusion"):
 * weak sources (< weak-evidence-floor) are dropped first and never veto;
 * all survivors must pairwise agree (<= agreement-tolerance-degrees) or the
 * whole result is insufficient-with-conflict - including against the OSM tag
 * (the cross-check: strong observed flow contradicting the map means stale
 * data, and this resolver never guesses); agreeing survivors combine by
 * noisy-OR and confidence-weighted circular mean.
 */
@Component
class DirectionEvidenceResolver(
    private val properties: AnalysisProperties,
) {

    fun fuse(sources: List<DirectionEvidence>): FusionResult {
        val (weak, survivors) = sources.partition { it.confidence < properties.weakEvidenceFloor }
        val weakEntries = weak.map { it.toEntry(EvidenceFate.DROPPED_WEAK) }

        if (survivors.isEmpty()) {
            return FusionResult.Insufficient(conflict = false, entries = weakEntries)
        }

        val anyDisagreement = survivors.indices.any { i ->
            (i + 1 until survivors.size).any { j ->
                BearingMath.angularDifferenceDegrees(
                    survivors[i].bearingDegrees,
                    survivors[j].bearingDegrees,
                ) > properties.agreementToleranceDegrees
            }
        }
        if (anyDisagreement) {
            return FusionResult.Insufficient(
                conflict = true,
                entries = weakEntries + survivors.map { it.toEntry(EvidenceFate.CONFLICT) },
            )
        }

        val fusedBearing = requireNotNull(
            BearingMath.weightedCircularMeanDegrees(
                survivors.map { it.bearingDegrees },
                survivors.map { it.confidence },
            ),
        ) { "survivors is non-empty with positive weights" }
        val directionConfidence = 1.0 - survivors.fold(1.0) { acc, s -> acc * (1.0 - s.confidence) }

        return FusionResult.Fused(
            bearingDegrees = fusedBearing,
            directionConfidence = directionConfidence,
            entries = weakEntries + survivors.map { it.toEntry(EvidenceFate.ACCEPTED) },
        )
    }

    private fun DirectionEvidence.toEntry(fate: EvidenceFate) =
        EvidenceEntry(kind, bearingDegrees, confidence, fate)
}
