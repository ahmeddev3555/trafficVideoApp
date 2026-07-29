package com.trafficwatch.server.reports

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * Binds `app.analysis.*` configuration for [ReportAnalysisJob]. [wrongWayToleranceDegrees]
 * is the angular tolerance (in degrees) for classifying a vehicle's absolute bearing as
 * "against" a street's legal direction - not a secret, so it carries a sane default.
 */
@Component
@ConfigurationProperties(prefix = "app.analysis")
data class AnalysisProperties(
    var wrongWayToleranceDegrees: Double = 60.0,
)
