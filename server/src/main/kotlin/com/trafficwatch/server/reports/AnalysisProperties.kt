package com.trafficwatch.server.reports

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * Binds `app.analysis.*` configuration for [ReportAnalysisJob]. Not a secret, so
 * [delayMs] carries a sane default (matching the ~10 second stub delay called for in the
 * plan) rather than requiring every environment to set it explicitly - mirrors
 * `com.trafficwatch.server.storage.StorageProperties`'s reasoning. Tests override this to a
 * tiny value (e.g. ~50ms) so they don't have to wait on the real default.
 */
@Component
@ConfigurationProperties(prefix = "app.analysis")
data class AnalysisProperties(
    var delayMs: Long = 10_000,
)
