package com.trafficwatch.server.reports

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "reports")
class Report(
    @Column(name = "user_id", nullable = false)
    var userId: UUID,

    @Column(name = "video_path", nullable = false)
    var videoPath: String,

    @Column(nullable = false)
    var latitude: BigDecimal,

    @Column(nullable = false)
    var longitude: BigDecimal,

    @Column(nullable = false)
    var accuracy: BigDecimal,

    @Column(nullable = false)
    var altitude: BigDecimal,

    @Column(nullable = false)
    var bearing: BigDecimal,

    @Column(nullable = false)
    var speed: BigDecimal,

    // Intentionally a timezone-less TIMESTAMP (unlike createdAt/updatedAt below) - the
    // Android client's recordedAt timestamp format quirk is handled at the parsing layer
    // in a later task, not here.
    @Column(name = "recorded_at", nullable = false)
    var recordedAt: LocalDateTime,

    @Column(name = "duration_ms", nullable = false)
    var durationMs: Long,

    @Column(name = "device_id", nullable = false)
    var deviceId: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: ReportStatus = ReportStatus.PENDING,

    @Column(name = "license_plate")
    var licensePlate: String? = null,

    @Column
    var confidence: BigDecimal? = null,

    @Column(name = "analysis_message", length = 500)
    var analysisMessage: String? = null,

    // From the Android client's compass snapshot - absent on submissions from app
    // versions predating that capability (see ReportAnalysisJob's "no compass heading"
    // rejection path).
    @Column(name = "compass_heading_degrees")
    var compassHeadingDegrees: BigDecimal? = null,

    // Populated from StreetDirectionResolver whenever a street name is known - even for
    // TwoWay/Unknown outcomes, not just CONFIRMED, since it's useful context regardless of
    // the violation decision.
    @Column(name = "street_name")
    var streetName: String? = null,

    // Path to the annotated (red-boxed) frame image of the flagged wrong-way vehicle, in
    // the same storage convention as videoPath - see WrongWayFrameStorageService. Null
    // means "no frame available" (report predates this feature, or annotation/storage
    // failed even though a wrong-way vehicle was found) - the app treats both the same.
    @Column(name = "wrong_way_frame_path")
    var wrongWayFramePath: String? = null,

    // How confident the analysis is that the flagged vehicle was genuinely moving the
    // wrong way (0.0-1.0) - separate from `confidence`, which is the license-plate OCR
    // confidence. See ReportAnalysisJob for the formula.
    @Column(name = "wrong_way_confidence")
    var wrongWayConfidence: BigDecimal? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now(),

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    var id: UUID? = null,
)
