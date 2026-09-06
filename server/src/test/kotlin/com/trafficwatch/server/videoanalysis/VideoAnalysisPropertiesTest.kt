package com.trafficwatch.server.videoanalysis

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class VideoAnalysisPropertiesTest {

    @Test
    fun `read timeout defaults to five minutes of headroom for one clip`() {
        // A single clip's CPU-only analysis runs ~150s on the prod box and longer for a
        // busy clip; the analysisExecutor serialises requests so this only covers one
        // clip's worst case, not contention. Must match application.yml's
        // app.video-analysis.read-timeout-ms.
        assertThat(VideoAnalysisProperties().readTimeoutMs).isEqualTo(300_000)
    }
}
