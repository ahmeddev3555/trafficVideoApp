package com.trafficwatch.server.geo

import com.trafficwatch.server.reports.AnalysisProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DirectionEvidenceResolverTest {

    private val resolver = DirectionEvidenceResolver(AnalysisProperties())

    @Test
    fun `osm alone fuses to its bearing with confidence one`() {
        val result = resolver.fuse(listOf(DirectionEvidence(EvidenceKind.OSM_TAG, 34.0, 1.0)))
        result as FusionResult.Fused
        assertEquals(34.0, result.bearingDegrees, 1e-9)
        assertEquals(1.0, result.directionConfidence, 1e-9)
        assertEquals(EvidenceFate.ACCEPTED, result.entries.single().fate)
    }

    @Test
    fun `agreeing sources combine by noisy-or and weighted circular mean`() {
        val result = resolver.fuse(
            listOf(
                DirectionEvidence(EvidenceKind.CLIP_CONSENSUS, 80.0, 0.6),
                DirectionEvidence(EvidenceKind.LEARNED_HISTORY, 100.0, 0.5),
            )
        )
        result as FusionResult.Fused
        // noisy-OR: 1 - 0.4*0.5 = 0.8
        assertEquals(0.8, result.directionConfidence, 1e-9)
        // Weighted mean pulled toward the higher-confidence source (80 side).
        assertTrue(result.bearingDegrees > 80.0 && result.bearingDegrees < 90.0)
        assertTrue(result.entries.all { it.fate == EvidenceFate.ACCEPTED })
    }

    @Test
    fun `strong disagreement forces insufficient with conflict`() {
        val result = resolver.fuse(
            listOf(
                DirectionEvidence(EvidenceKind.OSM_TAG, 0.0, 1.0),
                DirectionEvidence(EvidenceKind.CLIP_CONSENSUS, 180.0, 0.7),
            )
        )
        result as FusionResult.Insufficient
        assertTrue(result.conflict)
        assertTrue(result.entries.all { it.fate == EvidenceFate.CONFLICT })
    }

    @Test
    fun `weak source is dropped without vetoing`() {
        val result = resolver.fuse(
            listOf(
                DirectionEvidence(EvidenceKind.OSM_TAG, 0.0, 1.0),
                DirectionEvidence(EvidenceKind.CLIP_CONSENSUS, 180.0, 0.1), // below 0.2 floor
            )
        )
        result as FusionResult.Fused
        assertEquals(0.0, result.bearingDegrees, 1e-9)
        assertEquals(
            EvidenceFate.DROPPED_WEAK,
            result.entries.first { it.kind == EvidenceKind.CLIP_CONSENSUS }.fate,
        )
    }

    @Test
    fun `no sources is insufficient without conflict`() {
        val result = resolver.fuse(emptyList())
        result as FusionResult.Insufficient
        assertFalse(result.conflict)
        assertTrue(result.entries.isEmpty())
    }

    @Test
    fun `all sources weak is insufficient without conflict`() {
        val result = resolver.fuse(listOf(DirectionEvidence(EvidenceKind.CLIP_CONSENSUS, 90.0, 0.15)))
        result as FusionResult.Insufficient
        assertFalse(result.conflict)
        assertEquals(EvidenceFate.DROPPED_WEAK, result.entries.single().fate)
    }

    @Test
    fun `agreement works across the zero-360 wraparound`() {
        val result = resolver.fuse(
            listOf(
                DirectionEvidence(EvidenceKind.OSM_TAG, 350.0, 1.0),
                DirectionEvidence(EvidenceKind.CLIP_CONSENSUS, 10.0, 0.6),
            )
        )
        assertTrue(result is FusionResult.Fused)
    }
}
