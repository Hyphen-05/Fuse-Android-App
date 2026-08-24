package com.example.core.pacing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What survived `PacingAutoTuneEngineTest` when Tier E Phase 3 step 4 removed the tuning.
 *
 * That file's bulk tested the burst/fine-tune sequencing — the part whose only output was a pacing
 * value to store in a pref. There is no pref, so there is no sequencing and nothing left to
 * sequence-test. The verdict rule is what remains, and it is worth pinning precisely because it is
 * weaker than it looks: it is a connection check, not a throughput check.
 */
class LinkStressTestTest {

    @Test
    fun `still connected at the end passes`() {
        val result = LinkStressTest.evaluate(isConnected = true, achievedFps = 42, inFlightMs = 4.6)

        assertTrue(result.passed)
        assertEquals("Survived 120s at full rate", result.reason)
    }

    @Test
    fun `disconnected at the end fails`() {
        val result = LinkStressTest.evaluate(isConnected = false, achievedFps = 0, inFlightMs = 0.0)

        assertFalse(result.passed)
        assertEquals("Disconnected at end", result.reason)
    }

    @Test
    fun `measurements are carried through, not judged`() {
        // The documented weakness: a link delivering 1fps still passes if it is connected. Pinning
        // it so that if the criterion is ever tightened (queue depth, achieved-vs-target fps) this
        // test fails and says so, rather than the change landing silently.
        val crawling = LinkStressTest.evaluate(isConnected = true, achievedFps = 1, inFlightMs = 900.0)

        assertTrue(crawling.passed)
        assertEquals(1, crawling.achievedFps)
        assertEquals(900.0, crawling.inFlightMs, 0.001)
    }

    @Test
    fun `aborted runs never pass and keep their reason`() {
        val cancelled = LinkStressTest.aborted("Cancelled", achievedFps = 30, inFlightMs = 5.0)

        assertFalse(cancelled.passed)
        assertEquals("Cancelled", cancelled.reason)
        assertEquals(30, cancelled.achievedFps)
    }

    @Test
    fun `run is two minutes at full rate`() {
        assertEquals(120_000L, LinkStressTest.DURATION_MS)
        // 1ms is "as fast as the loop offers them" — the old engine's 0ms probe. If this grows, the
        // run stops being a full-rate stress and starts being a paced one.
        assertEquals(1L, LinkStressTest.SEND_DELAY_MS)
    }
}
