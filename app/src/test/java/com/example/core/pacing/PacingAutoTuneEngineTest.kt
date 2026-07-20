package com.example.core.pacing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PacingAutoTuneEngineTest {

    // ---- evaluateBurst -------------------------------------------------------------------

    @Test
    fun `evaluateBurst fails immediately when disconnected regardless of fps`() {
        val engine = PacingAutoTuneEngine()

        val result = engine.evaluateBurst(interval = 50, isConnected = false, currentFps = 999)

        assertFalse(result.isSuccess)
        assertEquals("Disconnected", result.reason)
        assertEquals(50, result.interval)
    }

    @Test
    fun `evaluateBurst succeeds when fps meets 60 percent of target`() {
        val engine = PacingAutoTuneEngine()
        // interval=100 -> targetFps=10, minAcceptable=6
        val result = engine.evaluateBurst(interval = 100, isConnected = true, currentFps = 6)

        assertTrue(result.isSuccess)
        assertEquals("Stable (~6 fps)", result.reason)
    }

    @Test
    fun `evaluateBurst fails when fps is below 60 percent of target and above the low-latency carve-out`() {
        val engine = PacingAutoTuneEngine()
        // interval=100 -> targetFps=10, minAcceptable=6; interval > 20 so the carve-out doesn't apply
        val result = engine.evaluateBurst(interval = 100, isConnected = true, currentFps = 5)

        assertFalse(result.isSuccess)
        assertEquals("Bottlenecked (~5 fps)", result.reason)
    }

    @Test
    fun `evaluateBurst carve-out passes low latency intervals with at least 20fps even if below target ratio`() {
        val engine = PacingAutoTuneEngine()
        // interval=0 -> targetFps=100, minAcceptable=30 (capped); 25fps < 30 but interval<=20 and fps>=20
        val result = engine.evaluateBurst(interval = 0, isConnected = true, currentFps = 25)

        assertTrue(result.isSuccess)
    }

    @Test
    fun `evaluateBurst caps minAcceptableFps at 30 for zero interval`() {
        val engine = PacingAutoTuneEngine()
        // interval=0 -> targetFps=100, 60% would be 60 but is coerced to 30
        val result = engine.evaluateBurst(interval = 0, isConnected = true, currentFps = 30)

        assertTrue(result.isSuccess)
    }

    // ---- evaluateStress --------------------------------------------------------------------

    @Test
    fun `evaluateStress passes when still connected`() {
        val engine = PacingAutoTuneEngine()
        val result = engine.evaluateStress(interval = 20, isConnected = true)

        assertTrue(result.isSuccess)
        assertEquals("Stress Passed", result.reason)
    }

    @Test
    fun `evaluateStress fails with disconnected-at-end reason when connection dropped`() {
        val engine = PacingAutoTuneEngine()
        val result = engine.evaluateStress(interval = 20, isConnected = false)

        assertFalse(result.isSuccess)
        assertEquals("Disconnected at end", result.reason)
    }

    // ---- start ------------------------------------------------------------------------------

    @Test
    fun `start with stressTestOnly runs a single stress probe at the original pacing`() {
        val engine = PacingAutoTuneEngine()

        val action = engine.start(originalPacingMs = 42, stressTestOnly = true)

        assertEquals(TuningAction.RunStress(42), action)
        assertEquals(TuningPhase.STRESS_TEST_ONLY, engine.phase)
    }

    @Test
    fun `start without stressTestOnly begins the burst phase at the first probe interval`() {
        val engine = PacingAutoTuneEngine()

        val action = engine.start(originalPacingMs = 100, stressTestOnly = false)

        assertEquals(TuningAction.RunBurst(PacingAutoTuneEngine.BURST_INTERVALS_MS.first()), action)
        assertEquals(TuningPhase.BURST_PHASE, engine.phase)
        assertEquals(0f, engine.burstProgress, 0.0001f)
    }

    @Test
    fun `start resets state from a previous run`() {
        val engine = PacingAutoTuneEngine()
        engine.start(originalPacingMs = 100, stressTestOnly = false)
        engine.onBurstResult(TuningResult(100, true, "Stable (~10 fps)"))

        engine.start(originalPacingMs = 80, stressTestOnly = true)

        assertTrue(engine.results.isEmpty())
        assertEquals(TuningPhase.STRESS_TEST_ONLY, engine.phase)
    }

    // ---- onBurstResult / burst-phase sequencing --------------------------------------------

    @Test
    fun `burst phase advances through the fixed interval list on success`() {
        val engine = PacingAutoTuneEngine()
        engine.start(originalPacingMs = 100, stressTestOnly = false)

        val next = engine.onBurstResult(TuningResult(100, true, "Stable (~10 fps)"))

        assertEquals(TuningAction.RunBurst(80), next)
        assertEquals(TuningPhase.BURST_PHASE, engine.phase)
        assertEquals(1f / 8f, engine.burstProgress, 0.0001f)
        assertEquals(listOf(TuningResult(100, true, "Burst: Stable (~10 fps)")), engine.results)
    }

    @Test
    fun `first burst failure switches to fine-tuning starting one above the failed interval`() {
        val engine = PacingAutoTuneEngine()
        engine.start(originalPacingMs = 100, stressTestOnly = false)
        engine.onBurstResult(TuningResult(100, true, "Stable (~10 fps)"))
        engine.onBurstResult(TuningResult(80, true, "Stable (~12 fps)"))

        val next = engine.onBurstResult(TuningResult(50, false, "Bottlenecked (~5 fps)"))

        assertEquals(TuningAction.RunBurst(51), next)
        assertEquals(TuningPhase.FINE_TUNING, engine.phase)
    }

    @Test
    fun `fine-tuning burst success moves to a stress test at the same candidate`() {
        val engine = PacingAutoTuneEngine()
        engine.start(originalPacingMs = 100, stressTestOnly = false)
        engine.onBurstResult(TuningResult(100, false, "Bottlenecked (~5 fps)")) // -> fine-tune at 101

        val next = engine.onBurstResult(TuningResult(101, true, "Stable (~10 fps)"))

        assertEquals(TuningAction.RunStress(101), next)
        assertEquals(TuningPhase.STRESS_TEST, engine.phase)
    }

    @Test
    fun `fine-tuning burst failure keeps incrementing the candidate`() {
        val engine = PacingAutoTuneEngine()
        engine.start(originalPacingMs = 100, stressTestOnly = false)
        engine.onBurstResult(TuningResult(100, false, "Bottlenecked (~5 fps)")) // -> fine-tune at 101

        val next = engine.onBurstResult(TuningResult(101, false, "Bottlenecked (~5 fps)"))

        assertEquals(TuningAction.RunBurst(102), next)
        assertEquals(TuningPhase.FINE_TUNING, engine.phase)
    }

    @Test
    fun `fine-tuning gives up once the candidate exceeds the 500ms cap`() {
        val engine = PacingAutoTuneEngine()
        engine.start(originalPacingMs = 100, stressTestOnly = false)
        engine.onBurstResult(TuningResult(100, false, "Bottlenecked (~5 fps)")) // failedInterval=100, candidate starts at 101

        // Drive the candidate up to 500 with repeated burst failures.
        var next: TuningAction = TuningAction.RunBurst(101)
        var candidate = 101
        while (candidate <= 500) {
            next = engine.onBurstResult(TuningResult(candidate, false, "Bottlenecked (~5 fps)"))
            candidate++
        }

        assertEquals(TuningAction.Finished(100, false), next)
        assertEquals(TuningPhase.FAILED, engine.phase)
    }

    @Test
    fun `all burst probes passing moves straight to a stress test at 0ms`() {
        val engine = PacingAutoTuneEngine()
        engine.start(originalPacingMs = 100, stressTestOnly = false)

        var next: TuningAction = TuningAction.Finished(-1, false)
        for (interval in PacingAutoTuneEngine.BURST_INTERVALS_MS) {
            next = engine.onBurstResult(TuningResult(interval, true, "Stable (~50 fps)"))
        }

        assertEquals(TuningAction.RunStress(0), next)
        assertEquals(TuningPhase.STRESS_TEST, engine.phase)
    }

    // ---- onStressResult ---------------------------------------------------------------------

    @Test
    fun `stress-test-only success finishes with that pacing`() {
        val engine = PacingAutoTuneEngine()
        engine.start(originalPacingMs = 42, stressTestOnly = true)

        val next = engine.onStressResult(TuningResult(42, true, "Stress Passed"))

        assertEquals(TuningAction.Finished(42, true), next)
        assertEquals(TuningPhase.COMPLETE, engine.phase)
        assertEquals(listOf(TuningResult(42, true, "Stress: Stress Passed")), engine.results)
    }

    @Test
    fun `stress-test-only failure finishes with the original pacing marked failed`() {
        val engine = PacingAutoTuneEngine()
        engine.start(originalPacingMs = 42, stressTestOnly = true)

        val next = engine.onStressResult(TuningResult(42, false, "Disconnected at end"))

        assertEquals(TuningAction.Finished(42, false), next)
        assertEquals(TuningPhase.FAILED, engine.phase)
    }

    @Test
    fun `stress test success after fine-tuning finishes with the candidate pacing`() {
        val engine = PacingAutoTuneEngine()
        engine.start(originalPacingMs = 100, stressTestOnly = false)
        engine.onBurstResult(TuningResult(100, false, "Bottlenecked (~5 fps)")) // -> fine-tune at 101
        engine.onBurstResult(TuningResult(101, true, "Stable (~10 fps)")) // -> stress at 101

        val next = engine.onStressResult(TuningResult(101, true, "Stress Passed"))

        assertEquals(TuningAction.Finished(101, true), next)
        assertEquals(TuningPhase.COMPLETE, engine.phase)
    }

    @Test
    fun `stress test failure after fine-tuning resumes incrementing burst candidates`() {
        val engine = PacingAutoTuneEngine()
        engine.start(originalPacingMs = 100, stressTestOnly = false)
        engine.onBurstResult(TuningResult(100, false, "Bottlenecked (~5 fps)")) // -> fine-tune at 101
        engine.onBurstResult(TuningResult(101, true, "Stable (~10 fps)")) // -> stress at 101

        val next = engine.onStressResult(TuningResult(101, false, "Disconnected at end"))

        assertEquals(TuningAction.RunBurst(102), next)
        assertEquals(TuningPhase.FINE_TUNING, engine.phase)
    }

    @Test
    fun `stress test failure on the all-bursts-passed path keeps incrementing stress candidates directly`() {
        val engine = PacingAutoTuneEngine()
        engine.start(originalPacingMs = 100, stressTestOnly = false)
        for (interval in PacingAutoTuneEngine.BURST_INTERVALS_MS) {
            engine.onBurstResult(TuningResult(interval, true, "Stable (~50 fps)"))
        }
        // now in STRESS_TEST at candidate 0, failedInterval == -1

        val next = engine.onStressResult(TuningResult(0, false, "Disconnected at end"))

        assertEquals(TuningAction.RunStress(1), next)
        assertEquals(TuningPhase.STRESS_TEST, engine.phase)
    }

    @Test
    fun `stress candidate cap on the all-bursts-passed path gives up above 500ms`() {
        val engine = PacingAutoTuneEngine()
        engine.start(originalPacingMs = 100, stressTestOnly = false)
        for (interval in PacingAutoTuneEngine.BURST_INTERVALS_MS) {
            engine.onBurstResult(TuningResult(interval, true, "Stable (~50 fps)"))
        }

        var next: TuningAction = TuningAction.Finished(-1, false)
        var candidate = 0
        while (candidate <= 500) {
            next = engine.onStressResult(TuningResult(candidate, false, "Disconnected at end"))
            candidate++
        }

        assertEquals(TuningAction.Finished(100, false), next)
        assertEquals(TuningPhase.FAILED, engine.phase)
    }

    // ---- results log accumulation ------------------------------------------------------------

    @Test
    fun `results accumulate labelled entries in call order`() {
        val engine = PacingAutoTuneEngine()
        engine.start(originalPacingMs = 100, stressTestOnly = false)
        engine.onBurstResult(TuningResult(100, true, "Stable (~10 fps)"))
        engine.onBurstResult(TuningResult(80, false, "Bottlenecked (~4 fps)"))
        engine.onBurstResult(TuningResult(81, true, "Stable (~10 fps)"))
        engine.onStressResult(TuningResult(81, true, "Stress Passed"))

        assertEquals(
            listOf(
                TuningResult(100, true, "Burst: Stable (~10 fps)"),
                TuningResult(80, false, "Burst: Bottlenecked (~4 fps)"),
                TuningResult(81, true, "Burst: Stable (~10 fps)"),
                TuningResult(81, true, "Stress: Stress Passed")
            ),
            engine.results
        )
    }
}
