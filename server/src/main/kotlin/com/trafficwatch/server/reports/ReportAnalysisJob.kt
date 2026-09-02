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
import com.trafficwatch.server.geo.OrientationTimeline
import com.trafficwatch.server.geo.StreetDirectionResolver
import com.trafficwatch.server.geo.UnknownReason
import com.trafficwatch.server.reports.dto.LocationSampleDto
import com.trafficwatch.server.reports.dto.RotationSampleDto
import com.trafficwatch.server.storage.VideoStorageService
import com.trafficwatch.server.storage.WrongWayFrameStorageService
import com.trafficwatch.server.videoanalysis.VideoAnalysisClient
import com.trafficwatch.server.videoanalysis.VideoAnalysisException
import com.trafficwatch.server.videoanalysis.dto.VehicleAnalysisResult
import com.trafficwatch.server.videoanalysis.dto.VideoAnalysisResponse
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
        val resolution = streetDirectionResolver.resolve(report.latitude, report.longitude, report.accuracy.toDouble())

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
                report.zoomRatio,
            )
        } catch (ex: VideoAnalysisException) {
            return AnalysisOutcome.rejected("Video analysis service unavailable: ${ex.message}", streetName)
        }

        // A report with no orientation source at all (no compass scalar, no samples)
        // means no vehicle's frame-relative bearing can be converted to a real-world
        // bearing - there is nothing to check against a legal direction. Rather than
        // bailing out before OSM/video analysis even run, street resolution and vehicle
        // detection above still happen and are reflected in the stored evidence
        // breakdown; only candidate direction-scoring is skipped, so this always lands
        // on REJECTED with a message that says why, distinct from "no violation found."
        val compassHeadingDegrees = report.compassHeadingDegrees
        // Parsed via parseLocationSamples/parseRotationSamples (not a raw != null check on
        // report.locationSamples/rotationSamples) because a String property mapped with
        // @JdbcTypeCode(SqlTypes.JSON) round-trips a Kotlin null through the DB as the
        // literal text "null" rather than a true SQL NULL - report.locationSamples is
        // non-null even for a report with no samples at all, so hasOrientationSource must be
        // driven by the actually-parsed (possibly empty) sample lists, not the raw field.
        val locationSamples = parseLocationSamples(report.locationSamples)
        val rotationSamples = parseRotationSamples(report.rotationSamples)
        val orientationTimeline = OrientationTimeline(locationSamples, rotationSamples)
        val hasOrientationSource = compassHeadingDegrees != null ||
            locationSamples.isNotEmpty() || rotationSamples.isNotEmpty()
        val flowVehicles = if (hasOrientationSource) {
            clipFlowAnalyzer.qualifyVehicles(
                analysis.vehicles,
                compassHeadingDegrees?.toDouble(),
                analysis.frameWidth,
                analysis.frameHeight,
                orientationTimeline,
            )
        } else {
            emptyList()
        }
        val historyEvidence = flowObservationService.historyEvidence(report.latitude, report.longitude)

        val evaluation = evaluateCandidates(flowVehicles, osmEvidence, historyEvidence)

        // Distinct from orientationMissing (report-level: no compass scalar AND no samples at
        // all): this is the case where the report DOES have an orientation source, and the
        // video service DID detect vehicles, but EVERY one of them individually failed to
        // resolve any orientation (no trackMidpointMs match against the timeline, and no
        // compass scalar to fall back to for that vehicle - see qualifyVehicles's tier
        // 1/2/3 chain) - so flowVehicles ends up empty for a reason that has nothing to do
        // with "nobody was driving the wrong way." Approximated (see
        // ClipFlowAnalyzer.qualifiesForFlowExceptOrientation's doc) by checking whether any
        // detected vehicle would otherwise have qualified were orientation available; a
        // vehicle that never had a chance for other reasons (bad corridor data, too few
        // frames, etc.) doesn't count as evidence of an orientation-specific failure.
        val orientationUnresolvedForAllVehicles = flowVehicles.isEmpty() && hasOrientationSource &&
            analysis.vehicles.any { clipFlowAnalyzer.qualifiesForFlowExceptOrientation(it, analysis.frameWidth, analysis.frameHeight) }

        val outcome = buildOutcome(
            report, evaluation, osmEvidence, historyEvidence, flowVehicles, streetName,
            orientationMissing = !hasOrientationSource,
            orientationUnresolvedForAllVehicles = orientationUnresolvedForAllVehicles,
        )

        ingestObservations(report, flowVehicles, evaluation.best?.flowVehicle)

        if (outcome.status == ReportStatus.REJECTED) {
            // Corroboration is computed over the RECEDING/steady traffic only: a vehicle whose
            // box is growing is a potential violator and must never corroborate the flow it opposes.
            val corroborationConsensus =
                strongestFlowConsensus(flowVehicles.filterNot { it.vehicle.scaleTrend == "growing" })
            val approachEligible = when (resolution) {
                is DirectionResolution.OneWay -> true
                is DirectionResolution.Unknown -> resolution.reason == UnknownReason.DIVIDED_CARRIAGEWAY
                else -> false
            }
            if (approachEligible) {
                tryStationaryApproachDetection(
                    report, analysis, orientationTimeline, streetName, resolution,
                    // Consulted only on the DIVIDED_CARRIAGEWAY branch; null on OneWay.
                    corroboration = (resolution as? DirectionResolution.Unknown)?.let { corroborationConsensus },
                )?.let { return it }
            }
        }

        return outcome
    }

    /**
     * The clip's strongest corridor consensus over [flowVehicles], no candidate excluded -
     * a "the scene is one coherent directional stream" signal. Reuses ClipFlowAnalyzer's
     * R-gate (returns null below consensus-min-resultant-length). Callers choose the
     * population: [buildOutcome]'s conflict veto passes every qualified vehicle, while the
     * stationary-approach corroboration gate passes the non-growing ones only.
     */
    private fun strongestFlowConsensus(flowVehicles: List<FlowVehicle>): CorridorConsensus? =
        flowVehicles.map { it.corridorId }.distinct()
            .mapNotNull { clipFlowAnalyzer.corridorConsensus(flowVehicles, it, excluding = null) }
            .maxByOrNull { it.clipConfidence }

    /**
     * Additive fallback (see the 2026-08-30 stationary-approach-detection spec): on a
     * verified-stationary camera pointed down a one-way street, a vehicle whose bounding
     * box grew sustainedly while at least three others receded is driving the wrong way -
     * a signal that needs no compass or OSM legal bearing. Only ever upgrades an
     * already-REJECTED outcome to CONFIRMED; returns null to leave the REJECTED outcome
     * untouched.
     *
     * On the `Unknown(DIVIDED_CARRIAGEWAY)` branch OSM gives no legal bearing, so the
     * detection is only trusted when [corroboration] - the strongest consensus among the
     * non-growing traffic - is LARGE (>= [AnalysisProperties.approachCorroborationMinMembers])
     * and TIGHTLY COHERENT (R >= [AnalysisProperties.approachCorroborationMinResultantLength])
     * and the grower is the LONE strong grower in the clip. Corridor co-membership between
     * the grower and that consensus is deliberately NOT required: on these roads the rider
     * opposes traffic from the median and is tracked in its own frame-space corridor
     * (verified in a 2026-08-31 production diagnostic). On `OneWay` the OSM tag already
     * asserts the legal direction, so none of this applies and [corroboration] is null.
     */
    private fun tryStationaryApproachDetection(
        report: Report,
        analysis: VideoAnalysisResponse,
        orientationTimeline: OrientationTimeline,
        streetName: String?,
        resolution: DirectionResolution,
        corroboration: CorridorConsensus?,
    ): AnalysisOutcome? {
        if (!orientationTimeline.wasStationaryThroughout()) return null

        // Mirrors ClipFlowAnalyzer.qualifyVehicles's "was a real frame analyzed" check:
        // absent/zero frame dimensions mean an older service version that never produced
        // usable geometry, so scale-trend signals cannot be trusted.
        val frameWidth = analysis.frameWidth
        val frameHeight = analysis.frameHeight
        if (frameWidth == null || frameWidth == 0 || frameHeight == null || frameHeight == 0) return null

        val vehicles = analysis.vehicles

        val minTrackFrames = 9 // == ClipFlowAnalyzer.MIN_TRACK_FRAMES
        val shrinking = vehicles.count {
            it.scaleTrend == "shrinking" && (it.trackFrameCount ?: 0) >= minTrackFrames
        }
        if (shrinking < 3) return null

        val strongGrowers = vehicles.filter {
            it.scaleTrend == "growing" &&
                it.scaleGrowthFraction >= analysisProperties.approachGrowthMin &&
                (it.trackFrameCount ?: 0) >= analysisProperties.approachMinFrames &&
                it.detectionConfidence >= analysisProperties.approachMinDetection
        }
        if (strongGrowers.isEmpty()) return null
        if (shrinking < 3 * strongGrowers.size) return null

        val best = strongGrowers.maxByOrNull { it.detectionConfidence } ?: return null

        // On the DIVIDED_CARRIAGEWAY (Unknown) branch OSM gives no legal bearing, so a
        // divided-road wrong-way approach is only trusted when a LARGE, TIGHTLY-COHERENT
        // receding stream corroborates it and the grower is a LONE anomaly. Corridor
        // co-membership is deliberately NOT required: the rider on these roads opposes
        // traffic from the median and is tracked in its own frame-space corridor (verified
        // in a 2026-08-31 production diagnostic). The strength of the receding consensus is
        // the safeguard against a stationary camera aimed upstream at a divided road, which
        // would see multiple legally-approaching growers rather than one. On OneWay the OSM
        // tag already asserts the legal direction, so none of this applies.
        if (resolution is DirectionResolution.Unknown) {
            val consensus = corroboration ?: return null
            if (consensus.memberCount < analysisProperties.approachCorroborationMinMembers) return null
            if (consensus.resultantLength < analysisProperties.approachCorroborationMinResultantLength) return null
            if (strongGrowers.size != 1) return null
        }

        if (best.detectionConfidence < analysisProperties.confirmationThreshold) return null

        return AnalysisOutcome(
            status = ReportStatus.CONFIRMED,
            licensePlate = best.plateText,
            confidence = best.plateConfidence?.let { BigDecimal.valueOf(it) },
            message = "Wrong-way vehicle approaching a stationary camera on ${streetName ?: "this street"}",
            streetName = streetName,
            wrongWayConfidence = BigDecimal.valueOf(best.detectionConfidence),
            wrongWayFramePath = annotateAndStoreFrame(
                best, requireNotNull(report.id) { "Report must have a generated id before analysis" },
            ),
            directionEvidenceJson = approachBreakdownJson(
                best, shrinking, strongGrowers.size, resolution,
                corroboration?.memberCount, corroboration?.resultantLength,
            ),
        )
    }

    private fun approachBreakdownJson(
        best: VehicleAnalysisResult,
        recedingCount: Int,
        strongGrowerCount: Int,
        resolution: DirectionResolution,
        corroborationMembers: Int?,
        corroborationResultantLength: Double?,
    ): String? = try {
        objectMapper.writeValueAsString(
            ApproachEvidenceBreakdown(
                resolutionState = when (resolution) {
                    is DirectionResolution.Unknown -> "UNKNOWN_${resolution.reason.name}"
                    is DirectionResolution.OneWay -> "ONE_WAY"
                    else -> "OTHER"
                },
                recedingCount = recedingCount,
                strongGrowerCount = strongGrowerCount,
                corroborationConsensusMembers = corroborationMembers,
                corroborationResultantLength = corroborationResultantLength,
                growthFraction = best.scaleGrowthFraction,
                trackFrames = best.trackFrameCount ?: 0,
                detectionConfidence = best.detectionConfidence,
                confirmationThreshold = analysisProperties.confirmationThreshold,
            ),
        )
    } catch (ex: Exception) {
        logger.warn("ReportAnalysisJob: failed to serialize approach evidence breakdown", ex)
        null
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
     * corridor's consensus, or against the fused legal bearing when alone or
     * when its corridor's overall consensus is unavailable but its own direction has real
     * peer support. Fusion is per-candidate because the clip-consensus source is the
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

            // A null consensus can mean either the corridor genuinely has no other
            // qualified member (the quiet-street case, where OSM/history evidence alone may
            // still score the candidate), or the corridor HAS other members but their
            // bearings are bimodal/dispersed, so the R-gate refused to elect one (a divided
            // road merged into one corridor, or a one-way street with both normal traffic
            // and a violator in frame). hasPeerSupport alone can't tell these apart - it is
            // vacuously false both when the candidate is genuinely alone (no one else to be
            // a peer) and when other members exist but none support its bearing - so
            // hasOtherCorridorMembers is still needed to identify the second case. Only skip
            // when other members exist AND none of them corroborate this candidate's OWN
            // specific direction - a lone bearing that happens to coincide with the illegal
            // direction by coincidence must never be trusted on OSM/history evidence alone,
            // but a direction corroborated by another observed vehicle is not a coincidence
            // and independent evidence may still apply. A genuinely alone candidate must
            // always proceed, exactly as before this fix.
            val hasOtherCorridorMembers = flowVehicles.any { it.corridorId == candidate.corridorId && it !== candidate }
            if (consensus == null && hasOtherCorridorMembers && !clipFlowAnalyzer.hasPeerSupport(flowVehicles, candidate)) {
                continue
            }

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
        orientationMissing: Boolean,
        orientationUnresolvedForAllVehicles: Boolean,
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
        val strongestClipEvidence = strongestFlowConsensus(flowVehicles)
            ?.let { DirectionEvidence(EvidenceKind.CLIP_CONSENSUS, it.bearingDegrees, it.clipConfidence) }
        val fallbackFusion = directionEvidenceResolver.fuse(
            listOfNotNull(osmEvidence, strongestClipEvidence, historyEvidence),
        )
        val entries = best?.fusion?.entries ?: fallbackFusion.entries

        val message = when {
            best != null -> "Possible wrong-way vehicle detected, but confidence was too low to confirm"
            evaluation.sawConflict || (fallbackFusion as? FusionResult.Insufficient)?.conflict == true ->
                "Conflicting direction evidence for this street"
            orientationMissing -> "No orientation data available for this report"
            orientationUnresolvedForAllVehicles -> "Vehicle orientation could not be determined for this report"
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
                candidateOrientationSource = best?.flowVehicle?.orientationSource?.name,
            ),
        )
    } catch (ex: Exception) {
        logger.warn("ReportAnalysisJob: failed to serialize evidence breakdown", ex)
        null
    }

    /**
     * Unlike ReportService.submit()'s parsing of the same field (which must tolerate
     * malformed/oversized client input before it's ever stored), by the time this reads
     * report.locationSamples back out, that JSON has already been validated and capped
     * at write time - a parse failure here would mean corrupted DB data, a genuine bug,
     * not user input to tolerate. No defensive try/catch.
     */
    private fun parseLocationSamples(json: String?): List<LocationSampleDto> {
        // The "null" check (not just == null) is not defensive tolerance of bad input: for a
        // String-typed column mapped with @JdbcTypeCode(SqlTypes.JSON), Hibernate persists a
        // Kotlin/Java null property value as the literal 4-character JSON-null text "null"
        // rather than a SQL NULL, and hands that same literal text back on read - so a report
        // saved with no location samples at all round-trips as the STRING "null", not Kotlin
        // null. This is the real, legitimate on-disk shape of "absent" for this column type,
        // not malformed data.
        if (json == null || json == "null") return emptyList()
        val parsed: List<LocationSampleDto> = objectMapper.readValue(
            json,
            objectMapper.typeFactory.constructCollectionType(List::class.java, LocationSampleDto::class.java),
        )
        return parsed
    }

    /** See [parseLocationSamples] - same reasoning, same trust-the-stored-invariant contract. */
    private fun parseRotationSamples(json: String?): List<RotationSampleDto> {
        if (json == null || json == "null") return emptyList()
        val parsed: List<RotationSampleDto> = objectMapper.readValue(
            json,
            objectMapper.typeFactory.constructCollectionType(List::class.java, RotationSampleDto::class.java),
        )
        return parsed
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
    val candidateOrientationSource: String?,
)

/**
 * Serialized (snake_case) into reports.direction_evidence when a report is confirmed by
 * the stationary-approach path instead of the bearing path. The `method` field is the
 * discriminator that tells a reader which shape this is.
 */
internal data class ApproachEvidenceBreakdown(
    val method: String = "stationary_approach",
    val resolutionState: String,
    val recedingCount: Int,
    val strongGrowerCount: Int,
    val corroborationConsensusMembers: Int?,
    val corroborationResultantLength: Double? = null,
    val growthFraction: Double,
    val trackFrames: Int,
    val detectionConfidence: Double,
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
