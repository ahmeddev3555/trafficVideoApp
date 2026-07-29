package com.trafficwatch.server.reports

import com.trafficwatch.server.geo.BearingMath
import com.trafficwatch.server.geo.DirectionResolution
import com.trafficwatch.server.geo.StreetDirectionResolver
import com.trafficwatch.server.storage.VideoStorageService
import com.trafficwatch.server.videoanalysis.VideoAnalysisClient
import com.trafficwatch.server.videoanalysis.VideoAnalysisException
import com.trafficwatch.server.videoanalysis.dto.VehicleAnalysisResult
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Real analysis pipeline: identifies the street a report's video was taken on and its legal
 * traffic direction (via [StreetDirectionResolver]), detects vehicles and their direction of
 * movement (via [VideoAnalysisClient]), and flips the report to `CONFIRMED` only for a
 * genuine wrong-way detection - `REJECTED` (with a specific [AnalysisOutcome.message]) for
 * every "insufficient data" case. See the plan's Architecture Overview for the full decision
 * flow.
 */
@Component
class ReportAnalysisJob(
    private val reportRepository: ReportRepository,
    private val analysisProperties: AnalysisProperties,
    private val streetDirectionResolver: StreetDirectionResolver,
    private val videoAnalysisClient: VideoAnalysisClient,
    private val videoStorageService: VideoStorageService,
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
        report.updatedAt = OffsetDateTime.now()

        reportRepository.save(report)
    }

    private fun determineOutcome(report: Report): AnalysisOutcome {
        val compassHeadingDegrees = report.compassHeadingDegrees
            ?: return AnalysisOutcome.rejected("Device compass heading unavailable for this report")

        val resolution = streetDirectionResolver.resolve(report.latitude, report.longitude)
        val (legalBearingDegrees, streetName) = when (resolution) {
            is DirectionResolution.NotFound ->
                return AnalysisOutcome.rejected("Could not identify a street at this location")
            is DirectionResolution.Unknown ->
                return AnalysisOutcome.rejected(
                    "Legal traffic direction unknown for this street",
                    resolution.streetName,
                )
            is DirectionResolution.TwoWay ->
                return AnalysisOutcome.rejected(
                    "Street is two-way; no wrong-way violation is possible here",
                    resolution.streetName,
                )
            is DirectionResolution.LookupFailed ->
                return AnalysisOutcome.rejected("Street lookup temporarily failed: ${resolution.reason}")
            is DirectionResolution.OneWay -> resolution.legalBearingDegrees to resolution.streetName
        }

        val vehicles = try {
            videoAnalysisClient.analyze(
                videoStorageService.resolve(report.videoPath),
                requireNotNull(report.id) { "Report must have a generated id before analysis" },
            )
        } catch (ex: VideoAnalysisException) {
            return AnalysisOutcome.rejected("Video analysis service unavailable: ${ex.message}", streetName)
        }

        val wrongWayVehicle = findBestWrongWayVehicle(
            vehicles,
            compassHeadingDegrees.toDouble(),
            legalBearingDegrees,
        )

        return if (wrongWayVehicle == null) {
            AnalysisOutcome.rejected("No vehicles detected moving against the legal direction", streetName)
        } else {
            AnalysisOutcome(
                status = ReportStatus.CONFIRMED,
                licensePlate = wrongWayVehicle.plateText,
                confidence = wrongWayVehicle.plateConfidence?.let { BigDecimal.valueOf(it) },
                message = "Wrong-way vehicle detected on ${streetName ?: "this street"}",
                streetName = streetName,
            )
        }
    }

    /**
     * Among [vehicles] whose absolute (compass-corrected) bearing falls within
     * [AnalysisProperties.wrongWayToleranceDegrees] of the illegal (opposite-of-legal)
     * direction, returns the one with the highest plate-read confidence (vehicles with no
     * plate read rank lowest, not excluded outright - a wrong-way detection with no
     * readable plate is still a real detection).
     */
    private fun findBestWrongWayVehicle(
        vehicles: List<VehicleAnalysisResult>,
        compassHeadingDegrees: Double,
        legalBearingDegrees: Double,
    ): VehicleAnalysisResult? {
        val illegalBearingDegrees = (legalBearingDegrees + 180.0) % 360.0

        return vehicles
            .mapNotNull { vehicle ->
                val frameBearing = vehicle.bearingDegrees ?: return@mapNotNull null
                val absoluteBearing = (compassHeadingDegrees + frameBearing) % 360.0
                val angularDistance = BearingMath.angularDifferenceDegrees(absoluteBearing, illegalBearingDegrees)
                if (angularDistance <= analysisProperties.wrongWayToleranceDegrees) vehicle else null
            }
            .maxByOrNull { it.plateConfidence ?: -1.0 }
    }
}

internal data class AnalysisOutcome(
    val status: ReportStatus,
    val licensePlate: String?,
    val confidence: BigDecimal?,
    val message: String,
    val streetName: String?,
) {
    companion object {
        fun rejected(message: String, streetName: String? = null) = AnalysisOutcome(
            status = ReportStatus.REJECTED,
            licensePlate = null,
            confidence = null,
            message = message,
            streetName = streetName,
        )
    }
}
