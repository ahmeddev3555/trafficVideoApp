package com.trafficwatch.server.videoanalysis

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * Binds `app.video-analysis.*` non-secret configuration. The shared-secret API key is
 * deliberately *not* here - see [VideoAnalysisClient]'s `@Value`-injected `apiKey`
 * constructor parameter, which mirrors `com.trafficwatch.server.auth.JwtService`'s
 * `app.jwt.secret` handling. Every property below has a code default (unlike a real
 * secret) because this class is a plain `@Component` rather than being registered via
 * `@EnableConfigurationProperties`/`@ConfigurationPropertiesScan`: Spring constructs it
 * through ordinary constructor autowiring first (falling back to each Kotlin default when
 * no matching bean exists), then Boot's relaxed binder overwrites these `var`s from the
 * environment - a non-defaulted parameter here would fail bean creation outright, not
 * "fail fast on a missing property" the way `@Value` does.
 */
@Component
@ConfigurationProperties(prefix = "app.video-analysis")
data class VideoAnalysisProperties(
    var baseUrl: String = "http://localhost:8000",
    var connectTimeoutMs: Int = 5000,
    var readTimeoutMs: Int = 180_000,
)
