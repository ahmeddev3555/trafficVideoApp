package com.trafficwatch.server.config

import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AsyncConfigTest {

    private val executor = AsyncConfig().analysisExecutor() as ThreadPoolTaskExecutor

    @Test
    fun `analysis executor is pinned to a single thread with a large queue`() {
        assertThat(executor.corePoolSize).isEqualTo(1)
        assertThat(executor.maxPoolSize).isEqualTo(1)
        // An untouched bounded LinkedBlockingQueue reports its full capacity as remaining.
        assertThat(executor.threadPoolExecutor.queue.remainingCapacity()).isEqualTo(100)
    }

    @Test
    fun `analysis executor never runs two tasks at once`() {
        val concurrent = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val started = CountDownLatch(3)
        val release = CountDownLatch(1)

        repeat(3) {
            executor.execute {
                val now = concurrent.incrementAndGet()
                maxConcurrent.updateAndGet { prev -> maxOf(prev, now) }
                started.countDown()
                release.await(2, TimeUnit.SECONDS)
                concurrent.decrementAndGet()
            }
        }

        // The first task is running and holding at the latch; the other two are queued.
        await().atMost(Duration.ofSeconds(2)).until { started.count <= 2L }
        assertThat(maxConcurrent.get()).isEqualTo(1)

        release.countDown()
        await().atMost(Duration.ofSeconds(3)).until { concurrent.get() == 0 && started.count == 0L }
        assertThat(maxConcurrent.get()).isEqualTo(1)
    }
}
