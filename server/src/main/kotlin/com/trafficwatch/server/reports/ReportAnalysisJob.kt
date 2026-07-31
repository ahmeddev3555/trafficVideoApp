package com.trafficwatch.server.reports

import com.fasterxml.jackson.databind.ObjectMapper
import com.trafficwatch.server.geo.BearingMath
import com.trafficwatch.server.geo.ClipFlowAnalyzer
import com.trafficwatch.server.geo.CorridorConsensus
import com.trafficwatch.server.geo.DirectionEvidence
import com.trafficwatch.server.geo.DirectionEvidenceResolver
import com.trafficwatch.server.geo.DirectionResolution
import com.trafficwatch.server.geo.EvidenceEntry
import com.trafficwatch.server.geo.EvidenceKind
import com.trafficwatch.server.geo.FlowObservationService
import com.trafficwatch.server.geo.FlowVehicle
import com.trafficwatch.server.geo.FusionResult
import com.trafficwatch.server.geo.StreetDirectionResolver
import com.trafficwatch.server.storage.VideoStorageService
import com.trafficwatch.server.storage.WrongWayFrameStorageService
import com.trafficwatch.server.videoanalysis.VideoAnalysisClient
import com.trafficwatch.server.videoanalysis.VideoAnalysisException
import com.trafficwatch.server.videoanalysis.dto.VehicleAnalysisResult
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID

/**
 * Real analysis pipeline: identifies the street a report's video was taken on and its legal
 * traffic direction (via [StreetDirectionResolver]), detects vehicles and their direction of
 * movement (via [VideoAnalysisClient]), and flips the report to `CONFIRMED` only for a
 * genuine wrong-way detection - `REJECTED` (with a specific [AnalysisOutcome.message]) for
 * every "insufficient data" case. On a genuine detection, also computes a wrong-way
 * confidence score and stores an annotated (red-boxed) frame of the flagged vehicle.
 */
@Component
class ReportAnalysisJob(
    private val reportRepository: ReportRepository,
    private val analysisProperties: AnalysisProperties,
    private val streetDirectionResolver: StreetDirectionResolver,
    private val videoAnalysisClient: VideoAnalysisClient,
    private val videoStorageService: VideoStorageService,
    private val wrongWayFrameStorageService: WrongWayFrameStorageService,
    private val clipFlowAnalyzer: ClipFlowAnalyzer,
    private val directionEvidenceResolver: DirectionEvidenceResolver,
    private val flowObservationService: FlowObservationService,
    private val objectMapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(ReportAnalysisJob::class.java)

    /**
     * Fire-and-forget entry point invoked by [ReportService.submit] after its transaction
     * commits (see [ReportService]'s `afterCommit` registration). Runs on
     * [com.trafficwatch.server.config.AsyncConfig.analysisExecutor], never on the calling
     * (HTTP request) thread.
     */
    @Async("analysisExecutor")
    fun analyze(reportId: UUID) {
        val report = reportRepository.findById(reportId).orElse(null)
        if (report == null) {
            logger.warn("ReportAnalysisJob: report {} no longer exists, skipping analysis", reportId)
            return
        }
        applyOutcome(report)
    }

    /**
     * The actual decision + persistence logic, split out from [analyze] so it can be
     * exercised deterministically in tests against a plain [Report] instance, with
     * [streetDirectionResolver] and [videoAnalysisClient] mocked - no real HTTP calls or
     * randomness involved.
     *
     * `internal` (not `private`) so `ReportAnalysisJobTest`, in the same Gradle module's
     * test source set, can call it directly.
     */
    internal fun applyOutcome(report: Report) {
        val outcome = determineOutcome(report)

        report.status = outcome.status
        report.licensePlate = outcome.licensePlate
        report.confidence = outcome.confidence
        report.analysisMessage = outcome.message
        report.streetName = outcome.streetName
        report.wrongWayConfidence = outcome.wrongWayConfidence
        report.wrongWayFramePath = outcome.wrongWayFramePath
        report.directionEvidence = outcome.directionEvidenceJson
        report.updatedAt = OffsetDateTime.now()

        reportRepository.save(report)
    }

    private fun determineOutcome(report: Report): AnalysisOutcome {
        val compassHeadingDegrees = report.compassHeadingDegrees
            ?: return AnalysisOutcome.rejected("Device compass heading unavailable for this report")

        val resolution = streetDirectionResolver.resolve(report.latitude, report.longitude)

        // TwoWay is the one terminal OSM outcome: an explicit oneway=no means
        // opposing traffic is legal, and video inference must never run (the
        // quiet-two-way-street false-positive guard). Everything else - Unknown,
        // NotFound, LookupFailed - just means the OSM evidence source is absent.
        if (resolution is DirectionResolution.TwoWay) {
            return AnalysisOutcome.rejected(
                "Street is two-way; no wrong-way violation is possible here",
                resolution.streetName,
            )
        }
        val osmEvidence = (resolution as? DirectionResolution.OneWay)?.let {
            DirectionEvidence(EvidenceKind.OSM_TAG, it.legalBearingDegrees, 1.0)
        }
        val streetName = when (resolution) {
            is DirectionResolution.OneWay -> resolution.streetName
            is DirectionResolution.Unknown -> resolution.streetName
            else -> null
        }

        val analysis = try {
            videoAnalysisClient.analyze(
                videoStorageService.resolve(report.videoPath),
                requireNotNull(report.id) { "Report must have a generated id before analysis" },
            )
        } catch (ex: VideoAnalysisException) {
            return AnalysisOutcome.rejected("Video analysis service unavailable: ${ex.message}", streetName)
        }

        val flowVehicles = clipFlowAnalyzer.qualifyVehicles(
            analysis.vehicles,
            compassHeadingDegrees.toDouble(),
            analysis.frameWidth,
            analysis.frameHeight,
        )
        val historyEvidence = flowObservationService.historyEvidence(report.latitude, report.longitude)

        val evaluation = evaluateCandidates(flowVehicles, osmEvidence, historyEvidence)

        val outcome = buildOutcome(report, evaluation, osmEvidence, historyEvidence, flowVehicles, streetName)

        ingestObservations(report, flowVehicles, evaluation.best?.flowVehicle)

        return outcome
    }

    private data class CandidateEvaluation(
        val best: ScoredCandidate?,
        val sawConflict: Boolean,
        val sawInsufficient: Boolean,
    )

    /**
     * Evaluates every qualified vehicle as a potential violator. Per spec:
     * a candidate moving WITH its own corridor's consensus is never a violator
     * (legal opposing stream on a divided road); a violator moves against its
     * corridor's consensus, or against the fused legal bearing when alone.
     * Fusion is per-candidate because the clip-consensus source is the
     * candidate's own corridor (excluding the candidate itself).
     */
    private fun evaluateCandidates(
        flowVehicles: List<FlowVehicle>,
        osmEvidence: DirectionEvidence?,
        historyEvidence: DirectionEvidence?,
    ): CandidateEvaluation {
        var best: ScoredCandidate? = null
        var sawConflict = false
        var sawInsufficient = false

        for (candidate in flowVehicles) {
            val consensus = clipFlowAnalyzer.corridorConsensus(flowVehicles, candidate.corridorId, candidate)
            if (consensus != null && clipFlowAnalyzer.movesWith(candidate, consensus)) {
                continue // gate 1: flows with its own corridor - never a violator
            }
            val clipEvidence = consensus?.let {
                DirectionEvidence(EvidenceKind.CLIP_CONSENSUS, it.bearingDegrees, it.clipConfidence)
            }

            when (val fusion = directionEvidenceResolver.fuse(listOfNotNull(osmEvidence, clipEvidence, historyEvidence))) {
                is FusionResult.Insufficient -> {
                    if (fusion.conflict) sawConflict = true else sawInsufficient = true
                }
                is FusionResult.Fused -> {
                    val illegalBearing = (fusion.bearingDegrees + 180.0) % 360.0
                    val angularDistance = BearingMath.angularDifferenceDegrees(
                        candidate.absoluteBearingDegrees,
                        illegalBearing,
                    )
                    if (angularDistance > analysisProperties.wrongWayToleranceDegrees) continue

                    val bearingMatchScore = 1.0 - (angularDistance / analysisProperties.wrongWayToleranceDegrees)
                    val finalScore = fusion.directionConfidence *
                        candidate.candidateQuality *
                        candidate.vehicle.detectionConfidence *
                        bearingMatchScore

                    if (best == null || finalScore > best!!.finalScore) {
                        best = ScoredCandidate(candidate, fusion, angularDistance, bearingMatchScore, finalScore)
                    }
                }
            }
        }
        return CandidateEvaluation(best, sawConflict, sawInsufficient)
    }

    private fun buildOutcome(
        report: Report,
        evaluation: CandidateEvaluation,
        osmEvidence: DirectionEvidence?,
        historyEvidence: DirectionEvidence?,
        flowVehicles: List<FlowVehicle>,
        streetName: String?,
    ): AnalysisOutcome {
        val best = evaluation.best
        if (best != null && best.finalScore >= analysisProperties.confirmationThreshold) {
            return AnalysisOutcome(
                status = ReportStatus.CONFIRMED,
                licensePlate = best.flowVehicle.vehicle.plateText,
                confidence = best.flowVehicle.vehicle.plateConfidence?.let { BigDecimal.valueOf(it) },
                message = "Wrong-way vehicle detected on ${streetName ?: "this street"}",
                streetName = streetName,
                wrongWayConfidence = BigDecimal.valueOf(best.finalScore),
                wrongWayFramePath = annotateAndStoreFrame(
                    best.flowVehicle.vehicle,
                    requireNotNull(report.id) { "Report must have a generated id before analysis" },
                ),
                directionEvidenceJson = breakdownJson(best.fusion.entries, best),
            )
        }

        // No confirmation. The fallback fusion must still include the clip's
        // strongest corridor consensus (computed with NO exclusion): when every
        // vehicle flows legally with its corridor, no per-candidate fusion ever
        // ran - yet a corridor unanimously opposing the OSM tag is exactly the
        // cross-check case, and the conflict veto must still surface here.
        val strongestClipEvidence = flowVehicles.map { it.corridorId }.distinct()
            .mapNotNull { clipFlowAnalyzer.corridorConsensus(flowVehicles, it, excluding = null) }
            .maxByOrNull { it.clipConfidence }
            ?.let { DirectionEvidence(EvidenceKind.CLIP_CONSENSUS, it.bearingDegrees, it.clipConfidence) }
        val fallbackFusion = directionEvidenceResolver.fuse(
            listOfNotNull(osmEvidence, strongestClipEvidence, historyEvidence),
        )
        val entries = best?.fusion?.entries ?: fallbackFusion.entries

        val message = when {
            best != null -> "Possible wrong-way vehicle detected, but confidence was too low to confirm"
            evaluation.sawConflict || (fallbackFusion as? FusionResult.Insufficient)?.conflict == true ->
                "Conflicting direction evidence for this street"
            fallbackFusion is FusionResult.Fused -> "No vehicles detected moving against the legal direction"
            else -> "Legal traffic direction could not be established for this street"
        }
        return AnalysisOutcome.rejected(
            message,
            streetName,
            directionEvidenceJson = breakdownJson(entries, best),
        )
    }

    /**
     * Ingestion happens for every analyzed report regardless of outcome - each
     * corridor's consensus computed EXCLUDING the evaluated (winning) candidate,
     * so a violator never teaches the learned DB. Failures are logged inside
     * FlowObservationService and never affect the report.
     */
    private fun ingestObservations(report: Report, flowVehicles: List<FlowVehicle>, excluded: FlowVehicle?) {
        val consensuses = flowVehicles.map { it.corridorId }.distinct().mapNotNull { corridorId ->
            clipFlowAnalyzer.corridorConsensus(flowVehicles, corridorId, excluded)
        }
        flowObservationService.ingest(report, consensuses)
    }

    private fun breakdownJson(entries: List<EvidenceEntry>, best: ScoredCandidate?): String? = try {
        objectMapper.writeValueAsString(
            EvidenceBreakdown(
                sources = entries,
                fusedBearingDegrees = best?.fusion?.bearingDegrees,
                directionConfidence = best?.fusion?.directionConfidence,
                candidateQuality = best?.flowVehicle?.candidateQuality,
                detectionConfidence = best?.flowVehicle?.vehicle?.detectionConfidence,
                bearingMatchScore = best?.bearingMatchScore,
                finalScore = best?.finalScore,
                confirmationThreshold = analysisProperties.confirmationThreshold,
            ),
        )
    } catch (ex: Exception) {
        logger.warn("ReportAnalysisJob: failed to serialize evidence breakdown", ex)
        null
    }

    /**
     * Draws a red box around the flagged vehicle in its representative frame and stores it,
     * for the report detail screen's "flagged vehicle" image. Never throws - a failure here
     * (missing frame data, a decode/encode error, a disk write failure) is logged and simply
     * leaves the report with no frame image, exactly like an old report predating this
     * feature; it must never block the CONFIRMED status the wrong-way detection itself
     * already earned.
     */
    private fun annotateAndStoreFrame(vehicle: VehicleAnalysisResult, reportId: UUID): String? {
        val boundingBox = vehicle.boundingBox ?: return null
        val frameJpegBase64 = vehicle.frameJpegBase64 ?: return null

        return try {
            val jpegBytes = Base64.getDecoder().decode(frameJpegBase64)
            val annotatedJpegBytes = FrameAnnotator.annotate(jpegBytes, boundingBox)
            wrongWayFrameStorageService.store(reportId, annotatedJpegBytes)
        } catch (ex: Exception) {
            logger.warn("ReportAnalysisJob: failed to annotate/store wrong-way frame for report {}", reportId, ex)
            null
        }
    }
}

/** Serialized (snake_case) into reports.direction_evidence - see the plan's breakdown shape. */
internal data class EvidenceBreakdown(
    val sources: List<EvidenceEntry>,
    val fusedBearingDegrees: Double?,
    val directionConfidence: Double?,
    val candidateQuality: Double?,
    val detectionConfidence: Double?,
    val bearingMatchScore: Double?,
    val finalScore: Double?,
    val confirmationThreshold: Double,
)

/** One fully-evaluated violation candidate. */
internal data class ScoredCandidate(
    val flowVehicle: FlowVehicle,
    val fusion: FusionResult.Fused,
    val angularDistanceDegrees: Double,
    val bearingMatchScore: Double,
    val finalScore: Double,
)

internal data class AnalysisOutcome(
    val status: ReportStatus,
    val licensePlate: String?,
    val confidence: BigDecimal?,
    val message: String,
    val streetName: String?,
    val wrongWayConfidence: BigDecimal? = null,
    val wrongWayFramePath: String? = null,
    val directionEvidenceJson: String? = null,
) {
    companion object {
        fun rejected(message: String, streetName: String? = null, directionEvidenceJson: String? = null) =
            AnalysisOutcome(
                status = ReportStatus.REJECTED,
                licensePlate = null,
                confidence = null,
                message = message,
                streetName = streetName,
                directionEvidenceJson = directionEvidenceJson,
            )
    }
}
