package com.trafficwatch.server.storage

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * Binds `app.storage.*` configuration. Unlike `app.jwt.secret` (a genuine secret that must
 * fail application startup if unset), a storage directory isn't sensitive, so
 * [videoDirectory] carries a sane default here rather than requiring every environment to
 * set it explicitly.
 */
@Component
@ConfigurationProperties(prefix = "app.storage")
data class StorageProperties(
    var videoDirectory: String = "storage/videos",
)
