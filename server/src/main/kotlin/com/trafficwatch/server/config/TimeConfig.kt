package com.trafficwatch.server.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * The application clock, injected wherever "now" is read so tests can advance time
 * deterministically. [ConditionalOnMissingBean] lets a test `@TestConfiguration`
 * provide a mutable clock without a bean-definition clash.
 */
@Configuration
class TimeConfig {
    @Bean
    @ConditionalOnMissingBean
    fun clock(): Clock = Clock.systemUTC()
}
