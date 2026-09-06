package com.trafficwatch.server.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.Executor

/**
 * Enables `@Async` methods (currently only
 * [com.trafficwatch.server.reports.ReportAnalysisJob.analyze]) and provides the dedicated
 * executor they run on.
 *
 * The executor bean is deliberately named `analysisExecutor` and referenced explicitly via
 * `@Async("analysisExecutor")` at the call site, rather than relying on Spring's default
 * `Executor`/`TaskExecutor` bean resolution - if more `Executor` beans are ever added for
 * other purposes, an unqualified `@Async` would become ambiguous (or silently pick the
 * wrong one). Naming it up front avoids that footgun even though only one executor exists
 * today.
 *
 * ## Single-threaded on purpose
 *
 * [com.trafficwatch.server.reports.ReportAnalysisJob.analyze] is CPU-bound end to end: it
 * makes a synchronous HTTP call to the Python `video-analysis` service, which runs
 * YOLOv8 + ByteTrack + EasyOCR on the whole clip on CPU. That service is a single process
 * on a 2-vCPU box; one clip already takes ~150s. Running two analyses at once does not
 * halve wall-clock - it makes *both* miss the client read timeout
 * (`app.video-analysis.read-timeout-ms`), and a timeout is turned into a permanent
 * `REJECTED` at [com.trafficwatch.server.reports.ReportAnalysisJob] (a citizen's report
 * discarded because the box was busy). So the pool is pinned to one thread: reports are
 * analysed strictly one at a time, each at full speed, and bursts wait in the queue.
 * `queueCapacity` is large enough that a realistic burst is absorbed rather than rejected.
 *
 * `setWaitForTasksToCompleteOnShutdown(true)` + a generous `awaitTerminationSeconds` let an
 * in-flight analysis finish across a redeploy instead of being killed mid-run (which would
 * strand its report in `PENDING`). A shutdown with nothing running returns immediately.
 */
@Configuration
@EnableAsync
class AsyncConfig {

    @Bean(name = ["analysisExecutor"])
    fun analysisExecutor(): Executor {
        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = 1
        executor.maxPoolSize = 1
        executor.queueCapacity = 100
        executor.setThreadNamePrefix("analysis-")
        executor.setWaitForTasksToCompleteOnShutdown(true)
        executor.setAwaitTerminationSeconds(300)
        executor.initialize()
        return executor
    }
}
