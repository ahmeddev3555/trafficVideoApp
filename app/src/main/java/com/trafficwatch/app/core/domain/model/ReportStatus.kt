package com.trafficwatch.app.core.domain.model

enum class ReportStatus {
    DRAFT,
    UPLOADING,
    UPLOAD_FAILED,
    PENDING,
    CONFIRMED,
    REJECTED;

    val isTerminal: Boolean
        get() = this == CONFIRMED || this == REJECTED
}
