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
 */
@Configuration
@EnableAsync
class AsyncConfig {

    @Bean(name = ["analysisExecutor"])
    fun analysisExecutor(): Executor {
        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = 2
        executor.maxPoolSize = 8
        executor.queueCapacity = 100
        executor.setThreadNamePrefix("analysis-")
        executor.initialize()
        return executor
    }
}
