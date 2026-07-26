package com.trafficwatch.server.reports

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.random.Random

/**
 * Stub stand-in for real computer-vision analysis (explicitly out of scope for this
 * project - see the plan). Simulates "analysis taking a while" with a plain
 * [Thread.sleep] rather than `CompletableFuture.delayedExecutor`/a scheduler: for a stub
 * whose only job is to eventually flip a status, `Thread.sleep` on a dedicated,
 * bounded-size executor thread ([com.trafficwatch.server.config.AsyncConfig.analysisExecutor])
 * is the simplest correct mechanism. The known gap - a server restart mid-delay silently
 * drops the job, leaving the report stuck `PENDING` forever - is an accepted limitation of
 * a stub, not something this task fixes.
 */
@Component
class ReportAnalysisJob(
    private val reportRepository: ReportRepository,
    private val analysisProperties: AnalysisProperties,
) {
    private val logger = LoggerFactory.getLogger(ReportAnalysisJob::class.java)

    /**
     * Fire-and-forget entry point invoked by [ReportService.submit] right after the report
     * is persisted. Runs on [com.trafficwatch.server.config.AsyncConfig.analysisExecutor],
     * never on the calling (HTTP request) thread, so the caller's response is never blocked
     * on [analysisProperties]'s delay.
     */
    @Async("analysisExecutor")
    fun analyze(reportId: UUID) {
        // This sleep is not just "simulated analysis time": ReportService.submit() calls
        // analyze() from inside its own @Transactional block, before that transaction has
        // committed. The only reason applyOutcome()'s findById() below reliably sees the
        // row is that this sleep - at any realistic delay value (test: 50-75ms, prod
        // default: ~10s) - gives the caller's transaction time to commit first. If this
        // sleep is ever removed, reordered to run after a repository read, or configured
        // down near zero (app.analysis.delay-ms), findById() can race the commit and miss
        // the not-yet-visible row - applyOutcome()'s "report no longer found" branch would
        // then swallow that as a log warning, silently leaving the report stuck PENDING
        // forever. This must remain the first statement in this method.
        Thread.sleep(analysisProperties.delayMs)
        applyOutcome(reportId, Random.nextInt(100))
    }

    /**
     * The actual decision + persistence logic, split out from [analyze] so it can be
     * exercised deterministically in tests: [roll] is a plain `0..99` value rather than
     * being drawn from [kotlin.random.Random] internally, so tests can assert the exact
     * 80/20 CONFIRMED/REJECTED boundary without depending on real randomness.
     *
     * `internal` (not `private`) so `ReportAnalysisJobTest`, in the same Gradle module's
     * test source set, can call it directly.
     */
    internal fun applyOutcome(reportId: UUID, roll: Int) {
        val report = reportRepository.findById(reportId).orElse(null)
        if (report == null) {
            // The report row could theoretically be gone by the time the delay elapses
            // (e.g. deleted out-of-band). There's nothing sensible to update in that case;
            // log and return rather than throwing out of an async method, where an
            // exception would only ever reach a logged "uncaught async exception" handler.
            logger.warn("ReportAnalysisJob: report {} no longer exists, skipping analysis", reportId)
            return
        }

        if (roll < 80) {
            report.status = ReportStatus.CONFIRMED
            report.licensePlate = "LEA-1234"
            report.confidence = BigDecimal("0.87")
            report.analysisMessage = "Stub analysis (placeholder, not real CV)"
        } else {
            report.status = ReportStatus.REJECTED
            report.licensePlate = null
            report.confidence = null
            report.analysisMessage = "Stub analysis (placeholder, not real CV)"
        }
        report.updatedAt = OffsetDateTime.now()

        reportRepository.save(report)
    }
}
